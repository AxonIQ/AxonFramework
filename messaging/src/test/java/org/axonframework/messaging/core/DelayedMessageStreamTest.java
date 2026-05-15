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

package org.axonframework.messaging.core;

import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link DelayedMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class DelayedMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        MessageStream<Message> testStream = MessageStream.fromIterable(messages);
        return DelayedMessageStream.create(CompletableFuture.completedFuture(testStream));
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        return DelayedMessageStream.createSingle(CompletableFuture.completedFuture(MessageStream.just(message)));
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        return new DelayedMessageStream.Empty<>(CompletableFuture.completedFuture(MessageStream.empty().cast()));
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        return DelayedMessageStream.create(CompletableFuture.completedFuture(
                MessageStream.fromIterable(messages)
                             .concatWith(MessageStream.failed(failure)))
        );
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                    "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Nested
    class Create {

        @Test
        void createForExecutionExceptionReturnsFailedMessageStreamWithCause() {
            RuntimeException expected = new RuntimeException("oops");

            CompletableFuture<Object> result = DelayedMessageStream.create(CompletableFuture.failedFuture(expected))
                                                                   .first().asCompletableFuture()
                                                                   .thenApply(Entry::message);

            assertTrue(result.isCompletedExceptionally());
            assertEquals(expected, result.exceptionNow());
        }

        @Test
        void entryBecomeVisibleWhenFutureCompletes_asCompletableFuture() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            CompletableFuture<MessageStream<Message>> testFuture = new CompletableFuture<>();

            MessageStream<?> testSubject = DelayedMessageStream.create(testFuture)
                                                               .onComplete(() -> invoked.set(true));

            CompletableFuture<?> result = testSubject.first().asCompletableFuture();
            assertFalse(result.isDone());
            assertFalse(invoked.get());

            testFuture.complete(MessageStream.just(createRandomMessage()));

            result = testSubject.first().asCompletableFuture();
            assertTrue(result.isDone());
            assertTrue(invoked.get());
        }

        @Test
        void createEntryBecomeVisibleWhenFutureCompletes_asFlux() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            Message expected = createRandomMessage();
            CompletableFuture<MessageStream<Message>> testFuture = new CompletableFuture<>();

            MessageStream<Message> testSubject = DelayedMessageStream.create(testFuture)
                                                                             .onComplete(() -> invoked.set(true));

            StepVerifier.create(FluxUtils.of(testSubject))
                        .verifyTimeout(Duration.ofMillis(250));
            // Verify timeout cancels the Flux, so we need to create the test subject again after this.
            assertFalse(invoked.get());

            testFuture.complete(MessageStream.just(expected));
            testSubject = DelayedMessageStream.create(testFuture)
                                              .onComplete(() -> invoked.set(true));

            StepVerifier.create(FluxUtils.of(testSubject))
                        .expectNextMatches(entry -> entry.message().equals(expected))
                        .verifyComplete();
            assertTrue(invoked.get());
        }

        @Test
        void reduceResultBecomesVisibleWhenFutureCompletes() {
            Message randomMessage = createRandomMessage();
            String expected = randomMessage.payloadAs(String.class) + randomMessage.payloadAs(String.class);
            MessageStream<Message> futureStream = completedTestSubject(List.of(randomMessage, randomMessage));
            CompletableFuture<MessageStream<Message>> testFuture = new CompletableFuture<>();

            MessageStream<Message> testSubject = DelayedMessageStream.create(testFuture);

            CompletableFuture<String> result = testSubject.reduce(
                    "",
                    (base, entry) -> base + entry.message().payload()
            );
            assertFalse(result.isDone());

            testFuture.complete(futureStream);

            assertTrue(result.isDone());
            assertEquals(expected, result.join());
        }

        @Test
        void closeWillCloseTheUnderlyingStreamWhenItResolves() {
            CompletableFuture<MessageStream<Message>> futureStream = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.create(futureStream);

            testSubject.close();

            assertTrue(testSubject.isCompleted());
            assertTrue(testSubject.error().isPresent());
        }

        @Test
        void closeWillCloseTheUnderlyingStreamImmediatelyWhenItHasResolved() {
            CompletableFuture<MessageStream<Message>> futureStream = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.create(futureStream);

            MessageStream<Message> mock = mock();
            futureStream.complete(mock);

            testSubject.close();
            verify(mock).close();
        }

        @Test
        void closeIsNotPropagatedWhenCompletableFutureCompletesExceptionally() {
            CompletableFuture<MessageStream<Message>> futureStream = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.create(futureStream);

            futureStream.completeExceptionally(new MockException("Simulating failure"));

            assertDoesNotThrow(testSubject::close);
        }

        @Test
        void shouldReturnEmptyWhenCallingNextOnFailingFuture() {
            CompletableFuture<MessageStream<Message>> future = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.create(future);

            assertFalse(testSubject.error().isPresent());
            assertFalse(testSubject.hasNextAvailable());

            future.completeExceptionally(new MockException("Simulating failure"));

            assertFalse(testSubject.hasNextAvailable());
            assertFalse(testSubject.next().isPresent());
            assertTrue(testSubject.isCompleted());
            assertTrue(testSubject.error().isPresent());
        }

        @Test
        void shouldReturnEmptyWhenCallingNextOnCancelledFuture() {
            CompletableFuture<MessageStream<Message>> future = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.create(future);

            assertFalse(testSubject.error().isPresent());
            assertFalse(testSubject.hasNextAvailable());

            future.cancel(true);

            assertFalse(testSubject.hasNextAvailable());
            assertFalse(testSubject.next().isPresent());
            assertTrue(testSubject.isCompleted());
            assertTrue(testSubject.error().isPresent());
        }

        @Test
        void shouldForwardNextCallAsSoonAsDelegateResolved() {
            CompletableFuture<MessageStream<Message>> future = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.create(future);
            assertFalse(testSubject.next().isPresent());
            assertFalse(testSubject.isCompleted());

            future.complete(MessageStream.just(createRandomMessage()));

            assertTrue(testSubject.next().isPresent());
            assertFalse(testSubject.isCompleted());  // streams never complete on non-empty next() as more data could be available
            assertFalse(testSubject.hasNextAvailable());  // perform action that must determine presence of next element
            assertTrue(testSubject.isCompleted());  // completes as it was proven there is no next element
        }
    }

    @Nested
    class CreateSingle {

        @Test
        void createForExecutionExceptionReturnsFailedMessageStreamWithCause() {
            RuntimeException expected = new RuntimeException("oops");

            CompletableFuture<Object> result = DelayedMessageStream.createSingle(CompletableFuture.failedFuture(expected))
                                                                   .first().asCompletableFuture()
                                                                   .thenApply(Entry::message);

            assertTrue(result.isCompletedExceptionally());
            assertEquals(expected, result.exceptionNow());
        }

        @Test
        void entryBecomeVisibleWhenFutureCompletes_asCompletableFuture() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            CompletableFuture<MessageStream.Single<Message>> testFuture = new CompletableFuture<>();

            MessageStream<?> testSubject = DelayedMessageStream.createSingle(testFuture)
                                                               .onComplete(() -> invoked.set(true));

            CompletableFuture<?> result = testSubject.first().asCompletableFuture();
            assertFalse(result.isDone());
            assertFalse(invoked.get());

            testFuture.complete(MessageStream.just(createRandomMessage()));

            result = testSubject.first().asCompletableFuture();
            assertTrue(result.isDone());
            assertTrue(invoked.get());
        }

        @Test
        void createEntryBecomeVisibleWhenFutureCompletes_asFlux() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            Message expected = createRandomMessage();
            CompletableFuture<MessageStream.Single<Message>> testFuture = new CompletableFuture<>();

            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(testFuture)
                                                                             .onComplete(() -> invoked.set(true));

            StepVerifier.create(FluxUtils.of(testSubject))
                        .verifyTimeout(Duration.ofMillis(250));
            // Verify timeout cancels the Flux, so we need to create the test subject again after this.
            assertFalse(invoked.get());

            testFuture.complete(MessageStream.just(expected));
            testSubject = DelayedMessageStream.createSingle(testFuture)
                                              .onComplete(() -> invoked.set(true));

            StepVerifier.create(FluxUtils.of(testSubject))
                        .expectNextMatches(entry -> entry.message().equals(expected))
                        .verifyComplete();
            assertTrue(invoked.get());
        }

        @Test
        void reduceResultBecomesVisibleWhenFutureCompletes() {
            Message randomMessage = createRandomMessage();
            String expected = randomMessage.payloadAs(String.class);
            MessageStream.Single<Message> futureStream = completedSingleStreamTestSubject(randomMessage);
            CompletableFuture<MessageStream.Single<Message>> testFuture = new CompletableFuture<>();

            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(testFuture);

            CompletableFuture<String> result = testSubject.reduce(
                    "",
                    (base, entry) -> base + entry.message().payload()
            );
            assertFalse(result.isDone());

            testFuture.complete(futureStream);

            assertTrue(result.isDone());
            assertEquals(expected, result.join());
        }

        @Test
        void closeWillCloseTheUnderlyingStreamWhenItResolves() {
            CompletableFuture<MessageStream.Single<Message>> futureStream = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(futureStream);

            testSubject.close();

            assertTrue(testSubject.isCompleted());
            assertTrue(testSubject.error().isPresent());
        }

        @Test
        void closeWillCloseTheUnderlyingStreamImmediatelyWhenItHasResolved() {
            CompletableFuture<MessageStream.Single<Message>> futureStream = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(futureStream);

            MessageStream.Single<Message> mock = mock();
            futureStream.complete(mock);

            testSubject.close();
            verify(mock).close();
        }

        @Test
        void closeIsNotPropagatedWhenCompletableFutureCompletesExceptionally() {
            CompletableFuture<MessageStream.Single<Message>> futureStream = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(futureStream);

            futureStream.completeExceptionally(new MockException("Simulating failure"));

            assertDoesNotThrow(testSubject::close);
        }

        @Test
        void shouldReturnEmptyWhenCallingNextOnFailingFuture() {
            CompletableFuture<MessageStream.Single<Message>> future = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(future);

            assertFalse(testSubject.error().isPresent());
            assertFalse(testSubject.hasNextAvailable());

            future.completeExceptionally(new MockException("Simulating failure"));

            assertFalse(testSubject.hasNextAvailable());
            assertFalse(testSubject.next().isPresent());
            assertTrue(testSubject.isCompleted());
            assertTrue(testSubject.error().isPresent());
        }

        @Test
        void shouldReturnEmptyWhenCallingNextOnCancelledFuture() {
            CompletableFuture<MessageStream.Single<Message>> future = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(future);

            assertFalse(testSubject.error().isPresent());
            assertFalse(testSubject.hasNextAvailable());

            future.cancel(true);

            assertFalse(testSubject.hasNextAvailable());
            assertFalse(testSubject.next().isPresent());
            assertTrue(testSubject.isCompleted());
            assertTrue(testSubject.error().isPresent());
        }

        @Test
        void shouldForwardNextCallAsSoonAsDelegateResolved() {
            CompletableFuture<MessageStream.Single<Message>> future = new CompletableFuture<>();
            MessageStream<Message> testSubject = DelayedMessageStream.createSingle(future);
            assertFalse(testSubject.next().isPresent());
            assertFalse(testSubject.isCompleted());

            future.complete(MessageStream.just(createRandomMessage()));

            assertTrue(testSubject.next().isPresent());
            assertFalse(testSubject.isCompleted());  // streams never complete on non-empty next() as more data could be available
            assertFalse(testSubject.hasNextAvailable());  // perform action that must determine presence of next element
            assertTrue(testSubject.isCompleted());  // completes as it was proven there is no next element
        }
    }
}