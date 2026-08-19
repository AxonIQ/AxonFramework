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

package org.axonframework.common;

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Marc Gathier
 */
class ProcessUtilsTest {

    @Test
    void executeWithRetry() {
        AtomicLong retryCounter = new AtomicLong();

        ProcessUtils.executeWithRetry(() -> {
                                          if (retryCounter.getAndIncrement() < 5) {
                                              throw new IllegalArgumentException("Waiting for 5");
                                          }
                                      }, e -> ExceptionUtils.findException(e, IllegalArgumentException.class).isPresent(),
                                      100, TimeUnit.MILLISECONDS, 10);

        assertEquals(6, retryCounter.get());
    }

    @Test
    void executeWithRetryStops() {
        AtomicLong retryCounter = new AtomicLong();

        assertThrows(IllegalArgumentException.class, () ->
                ProcessUtils.executeWithRetry(() -> {
                            if (retryCounter.getAndIncrement() < 11) {
                                throw new IllegalArgumentException("Waiting for 11");
                            }
                        },
                        e -> ExceptionUtils.findException(e, IllegalArgumentException.class).isPresent(),
                        100,
                        TimeUnit.MILLISECONDS,
                        10)
        );
    }

    @Test
    void executeWithRetryImmediatelyStopsOnOther() {
        AtomicLong retryCounter = new AtomicLong();

        try {
            ProcessUtils.executeWithRetry(() -> {
                if (retryCounter.getAndIncrement() < 11) {
                    throw new IllegalArgumentException("Waiting for 11");
                }
            }, e -> false, 100, TimeUnit.MILLISECONDS, 10);
            fail("Should not get here");
        } catch (Exception ex) {
            assertTrue(ex instanceof IllegalArgumentException);
            assertEquals(1, retryCounter.get());
        }
    }

    @Test
    void executeUntilTrueRetries(){
        AtomicLong retryCounter = new AtomicLong();
        ProcessUtils.executeUntilTrue(() -> retryCounter.getAndIncrement() >= 1, 10L, 10L);
        assertEquals(2, retryCounter.get());
    }

    @Test
    void executeUntilTrueThrowsWhenMaxRetriesReached(){
        AtomicLong retryCounter = new AtomicLong();
        assertThrows(ProcessRetriesExhaustedException.class, () ->
                ProcessUtils.executeUntilTrue(() -> retryCounter.getAndIncrement() >= 100, 1L, 10L)
        );
        assertEquals(10, retryCounter.get());
    }

    @Nested
    class ExecuteUntilTrueAsync {

        @Test
        void completesImmediatelyWhenActionReturnsTrueOnFirstCall() {
            // given
            var executor = Executors.newSingleThreadExecutor();

            // when
            CompletableFuture<Void> result = ProcessUtils.executeUntilTrue(
                    () -> CompletableFuture.completedFuture(true), 10L, 3L, executor
            );

            // then
            assertThat(result).succeedsWithin(1, TimeUnit.SECONDS);
        }

        @Test
        void retriesUntilActionReturnsTrue() {
            // given
            AtomicLong callCount = new AtomicLong();
            var executor = Executors.newSingleThreadExecutor();

            // when
            CompletableFuture<Void> result = ProcessUtils.executeUntilTrue(
                    () -> CompletableFuture.completedFuture(callCount.incrementAndGet() >= 3),
                    10L, 5L, executor
            );

            // then
            assertThat(result).succeedsWithin(1, TimeUnit.SECONDS);
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        void failsWithProcessRetriesExhaustedExceptionWhenMaxTriesReached() {
            // given
            AtomicLong callCount = new AtomicLong();
            var executor = Executors.newSingleThreadExecutor();

            // when
            CompletableFuture<Void> result = ProcessUtils.executeUntilTrue(
                    () -> {
                        callCount.incrementAndGet();
                        return CompletableFuture.completedFuture(false);
                    },
                    10L, 5L, executor
            );

            // then
            assertThat(result).failsWithin(1, TimeUnit.SECONDS)
                              .withThrowableOfType(Exception.class)
                              .havingCause()
                              .isInstanceOf(ProcessRetriesExhaustedException.class);
            assertThat(callCount.get()).isEqualTo(5);
        }

        @Test
        void propagatesFailedFutureFromActionWithoutRetrying() {
            // given
            AtomicLong callCount = new AtomicLong();
            var executor = Executors.newSingleThreadExecutor();
            var cause = new RuntimeException("action failed");

            // when
            CompletableFuture<Void> result = ProcessUtils.executeUntilTrue(
                    () -> {
                        callCount.incrementAndGet();
                        return CompletableFuture.failedFuture(cause);
                    },
                    10L, 5L, executor
            );

            // then - failed future propagates immediately, action is only called once
            assertThat(result).failsWithin(1, TimeUnit.SECONDS)
                              .withThrowableOfType(Exception.class)
                              .havingCause()
                              .isEqualTo(cause);
            assertThat(callCount.get()).isEqualTo(1);
        }
    }
}