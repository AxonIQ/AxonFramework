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

package org.axonframework.messaging.core.unitofwork;

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QueueMessageStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link ProcessingContext#requiresSameThreadInvocations()}.
 *
 * @author John Hendrikx
 */
class ProcessingContextSameThreadInvocationsTest {

    private static boolean requiresSameThreadInvocations(UnitOfWorkFactory factory) {
        return factory.create()
                      .executeWithResult(context -> CompletableFuture.completedFuture(
                              context.requiresSameThreadInvocations()
                      ))
                      .orTimeout(5, TimeUnit.SECONDS)
                      .join();
    }

    @Nested
    class WhenTheUnitOfWorkAllowsAsyncProcessing {

        @Test
        void shouldNotRequireSameThreadInvocations() {
            // given
            UnitOfWorkFactory factory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);

            // when / then
            assertThat(requiresSameThreadInvocations(factory)).isFalse();
        }
    }

    @Nested
    class WhenTheUnitOfWorkMustWorkOnTheCallingThread {

        private final UnitOfWorkFactory factory = new SimpleUnitOfWorkFactory(
                EmptyApplicationContext.INSTANCE,
                UnitOfWorkConfiguration::forcedSameThreadInvocation
        );

        @Test
        void shouldRequireSameThreadInvocations() {
            assertThat(requiresSameThreadInvocations(factory)).isTrue();
        }

        @Test
        void shouldBeKeptByAContextBranchedOffFromTheUnitOfWorkContext() {
            // when
            boolean required = factory.create()
                                      .executeWithResult(context -> CompletableFuture.completedFuture(
                                              context.withResource(Context.ResourceKey.withLabel("other"), "value")
                                                     .requiresSameThreadInvocations()
                                      ))
                                      .orTimeout(5, TimeUnit.SECONDS)
                                      .join();

            // then
            assertThat(required).isTrue();
        }

        @Test
        void shouldLetAReductionReadEverythingOnTheCallingThreadWhenEntriesArriveLater() {
            // given a stream that has nothing available at first, and gets its entries from another thread
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            Set<Thread> accumulatingThreads = ConcurrentHashMap.newKeySet();
            Thread producer = new Thread(() -> {
                sleep(100);
                source.offer(new GenericMessage(new MessageType("test"), "one"), Context.empty());
                sleep(100);
                source.offer(new GenericMessage(new MessageType("test"), "two"), Context.empty());
                source.seal();
            });
            AtomicReference<Thread> callingThread = new AtomicReference<>();

            // when
            producer.start();

            Integer count = factory.create().executeWithResult(context -> {
                callingThread.set(Thread.currentThread());

                return context.requiresSameThreadInvocations()
                        ? source.reduce(0, (total, entry) -> {
                            accumulatingThreads.add(Thread.currentThread());
                            return total + 1;
                        }, null)
                        : source.reduce(0, (total, entry) -> total + 1);
            }).orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(count).isEqualTo(2);
            assertThat(accumulatingThreads).containsExactly(callingThread.get());
        }
    }

    @Nested
    class WhenTheContextHasNoUnitOfWork {

        @Test
        void shouldNotRequireSameThreadInvocations() {
            assertThat(new StubProcessingContext().requiresSameThreadInvocations()).isFalse();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
