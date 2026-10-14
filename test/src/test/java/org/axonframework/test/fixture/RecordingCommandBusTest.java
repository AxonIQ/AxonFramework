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

package org.axonframework.test.fixture;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingCommandBusTest {

    private final CommandBus delegate = new CommandBus() {
        @Override
        public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                                @Nullable ProcessingContext processingContext) {
            return CompletableFuture.completedFuture(resultFor(command));
        }

        @Override
        public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
            return this;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // No state to describe.
        }
    };

    private final RecordingCommandBus testSubject = new RecordingCommandBus(delegate);

    @Nested
    class DispatchOrder {

        @Test
        void recordedCommandsAreReturnedInDispatchOrder() {
            // given a number of commands large enough that hash-based ordering would differ from insertion order
            List<CommandMessage> dispatched = commands(25);

            // when
            dispatched.forEach(command -> testSubject.dispatch(command, null).join());

            // then
            assertThat(testSubject.recordedCommands()).containsExactlyElementsOf(dispatched);
        }

        @Test
        void recordedResultsAreReturnedInDispatchOrder() {
            // given
            List<CommandMessage> dispatched = commands(25);

            // when
            dispatched.forEach(command -> testSubject.dispatch(command, null).join());

            // then
            assertThat(testSubject.recorded().keySet()).containsExactlyElementsOf(dispatched);
        }

        @Test
        void dispatchOrderSurvivesAReset() {
            // given a first batch that is cleared away
            commands(25).forEach(command -> testSubject.dispatch(command, null).join());
            testSubject.reset();

            // when
            List<CommandMessage> secondBatch = commands(25);
            secondBatch.forEach(command -> testSubject.dispatch(command, null).join());

            // then
            assertThat(testSubject.recordedCommands()).containsExactlyElementsOf(secondBatch);
        }
    }

    @Nested
    class Results {

        @Test
        void theResultOfEachDispatchedCommandIsRecorded() {
            // given
            CommandMessage command = command("only-one");

            // when
            testSubject.dispatch(command, null).join();

            // then
            assertThat(testSubject.resultOf(command)).isNotNull()
                                                     .extracting(Message::payload)
                                                     .isEqualTo("result-of-only-one");
        }

        @Test
        void resultIsRecordedBeforeTheReturnedFutureCompletes() {
            // given
            CompletableFuture<CommandResultMessage> delegateResult = new CompletableFuture<>();
            RecordingCommandBus asynchronous = new RecordingCommandBus(new CommandBus() {
                @Override
                public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                                        @Nullable ProcessingContext context) {
                    return delegateResult;
                }

                @Override
                public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
                    return this;
                }

                @Override
                public void describeTo(ComponentDescriptor descriptor) {
                    // No state to describe.
                }
            });
            CommandMessage command = command("asynchronous");
            CommandResultMessage expectedResult = resultFor(command);

            // when a caller observes the recording from a continuation of the returned dispatch future
            CompletableFuture<Message> recordedWhenDispatchCompletes =
                    asynchronous.dispatch(command, null).thenApply(ignored -> asynchronous.resultOf(command));
            delegateResult.complete(expectedResult);

            // then
            assertThat(recordedWhenDispatchCompletes).isCompletedWithValue(expectedResult);
        }

        /**
         * A command whose dispatch failed never gets a result, so the recording keeps it mapped to {@code null}. The
         * recordings must still be readable, because that is exactly the state a test asserting on a failure is in.
         */
        @Test
        void aCommandWithoutAResultIsStillRecorded() {
            // given a bus whose delegate always fails
            RecordingCommandBus failing = new RecordingCommandBus(new CommandBus() {
                @Override
                public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                                        @Nullable ProcessingContext context) {
                    return CompletableFuture.failedFuture(new IllegalStateException("no handler"));
                }

                @Override
                public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
                    return this;
                }

                @Override
                public void describeTo(ComponentDescriptor descriptor) {
                    // No state to describe.
                }
            });
            CommandMessage command = command("never-handled");

            // when
            assertThat(failing.dispatch(command, null)).isCompletedExceptionally();

            // then
            assertThat(failing.recordedCommands()).containsExactly(command);
            assertThat(failing.recorded()).containsOnlyKeys(command);
            assertThat(failing.resultOf(command)).isNull();
        }
    }

    @Nested
    class ConcurrentDispatching {

        /**
         * A pooled streaming event processor dispatches from several worker threads at once. An unsynchronized map
         * loses entries under that load, which would show up as a flaky assertion rather than as an obvious failure.
         */
        @Test
        void everyCommandDispatchedConcurrentlyIsRecorded() throws Exception {
            // given
            int threads = 8;
            int commandsPerThread = 250;
            List<CommandMessage> dispatched = commands(threads * commandsPerThread);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            // when
            try {
                for (int thread = 0; thread < threads; thread++) {
                    int offset = thread * commandsPerThread;
                    executor.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < commandsPerThread; i++) {
                                testSubject.dispatch(dispatched.get(offset + i), null).join();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            // then
            assertThat(testSubject.recordedCommands()).containsExactlyInAnyOrderElementsOf(dispatched);
        }
    }

    private static List<CommandMessage> commands(int count) {
        return IntStream.range(0, count)
                        .mapToObj(i -> command("command-" + i))
                        .toList();
    }

    private static CommandMessage command(String payload) {
        return new GenericCommandMessage(new MessageType("test-command"), payload);
    }

    private static CommandResultMessage resultFor(CommandMessage command) {
        return new GenericCommandResultMessage(new MessageType("test-result"), "result-of-" + command.payload());
    }
}
