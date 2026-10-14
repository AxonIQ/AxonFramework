/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.common.tx;

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link MaterializingResource}.
 *
 * @author John Hendrikx
 */
class MaterializingResourceTest {

    private final AtomicInteger supplierInvocations = new AtomicInteger();

    private static boolean hasActiveResource(MaterializingResource<?> executor) {
        return executor.withMaterializationShielded(active -> active);
    }

    @Nested
    class Caching {

        @Test
        void shouldMaterializeTheResourceOnlyOnceAcrossMultipleApplyCalls() throws Exception {
            MaterializingResource<String> testSubject = new MaterializingResource<>(
                    () -> "resource-" + supplierInvocations.incrementAndGet()
            );

            assertThat(hasActiveResource(testSubject)).isFalse();

            String first = testSubject.apply(r -> r).get();
            String second = testSubject.apply(r -> r).get();

            assertThat(first).isEqualTo("resource-1");
            assertThat(second).isEqualTo("resource-1");
            assertThat(supplierInvocations).hasValue(1);
            assertThat(hasActiveResource(testSubject)).isTrue();
        }

        @Test
        void shouldNotMarkTheResourceActiveWhenSupplierFails() {
            MaterializingResource<String> testSubject = new MaterializingResource<>(() -> {
                throw new IllegalStateException("supplier failed");
            });

            CompletableFuture<String> result = testSubject.apply(r -> r);

            assertThat(result).isCompletedExceptionally();
            assertThat(hasActiveResource(testSubject)).isFalse();
        }

        @Test
        void shouldFailTheFutureWhenTheAppliedFunctionThrows() {
            MaterializingResource<String> testSubject = new MaterializingResource<>(() -> "resource");

            CompletableFuture<String> result = testSubject.apply(r -> {
                throw new IllegalStateException("function failed");
            });

            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            // the resource itself was still successfully obtained, even though using it failed
            assertThat(hasActiveResource(testSubject)).isTrue();
        }
    }

    @Nested
    class MaterializationGuard {

        @Test
        void shouldBlockMaterializationWhileADecisionIsBeingShielded() throws Exception {
            MaterializingResource<String> testSubject = new MaterializingResource<>(
                    () -> "resource-" + supplierInvocations.incrementAndGet()
            );
            CountDownLatch decisionStarted = new CountDownLatch(1);
            CountDownLatch releaseDecision = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            AtomicInteger materializedWhileShielded = new AtomicInteger(-1);
            AtomicReference<CompletableFuture<String>> writerResult = new AtomicReference<>();

            Thread reader = new Thread(() -> testSubject.withMaterializationShielded(active -> {
                decisionStarted.countDown();
                await(releaseDecision);
                materializedWhileShielded.set(supplierInvocations.get());
                return null;
            }));

            reader.start();

            assertThat(decisionStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // runs on its own thread: apply() blocks synchronously on the write lock, so calling
            // it on this (the test) thread would itself block before we get a chance to observe it
            Thread writer = new Thread(() -> {
                writerResult.set(testSubject.apply(r -> r));
                writerDone.countDown();
            });

            writer.start();

            // give the writer every chance to (incorrectly) race ahead before the guard is released
            assertThat(writerDone.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(supplierInvocations).hasValue(0);

            releaseDecision.countDown();
            reader.join(2000);

            assertThat(writerDone.await(2, TimeUnit.SECONDS)).isTrue();
            writer.join(2000);

            assertThat(materializedWhileShielded).hasValue(0);
            assertThat(supplierInvocations).hasValue(1);
            assertThat(writerResult.get()).isCompletedWithValue("resource-1");
        }

        @Test
        void shouldNotBlockReusingAnAlreadyActiveResourceWhileShielded() throws Exception {
            MaterializingResource<String> testSubject = new MaterializingResource<>(() -> "resource");

            // materialize upfront
            testSubject.apply(r -> r).get();

            assertThat(hasActiveResource(testSubject)).isTrue();

            // reusing an already-active resource from within a shielded decision must not self-deadlock
            String result = testSubject.withMaterializationShielded(active ->
                    testSubject.apply(r -> r).join()
            );

            assertThat(result).isEqualTo("resource");
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    class SerializedUse {

        @Test
        void shouldSerializeConcurrentUseOfTheSameResource() throws Exception {
            AtomicInteger concurrentUsers = new AtomicInteger();
            AtomicInteger maxObservedConcurrentUsers = new AtomicInteger();
            MaterializingResource<String> testSubject = new MaterializingResource<>(() -> "resource");

            Runnable useResource = () -> testSubject.apply(r -> {
                int current = concurrentUsers.incrementAndGet();
                maxObservedConcurrentUsers.updateAndGet(max -> Math.max(max, current));
                try {
                    Thread.sleep(50);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentUsers.decrementAndGet();
                return null;
            }).join();

            Thread t1 = new Thread(useResource);
            Thread t2 = new Thread(useResource);

            t1.start();
            t2.start();
            t1.join();
            t2.join();

            assertThat(maxObservedConcurrentUsers).hasValue(1);
        }
    }
}
