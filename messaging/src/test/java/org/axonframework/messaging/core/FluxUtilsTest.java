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

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link FluxUtils#of(MessageStream)}.
 * <p>
 * Guards that the source {@link MessageStream} is {@link MessageStream#close() closed} on <em>every</em> terminal
 * signal of the resulting {@link reactor.core.publisher.Flux Flux} — completion, error, and cancellation — so resources
 * held by the source (e.g. a subscription query registration) are always released. The completion case is a regression
 * guard: cleanup used to be wired to cancellation only, so a normally-completing stream leaked its source.
 *
 * @author Allard Buijze
 */
class FluxUtilsTest {

    @Test
    void closesSourceWhenStreamCompletes() {
        CloseTrackingMessageStream<Message> source = new CloseTrackingMessageStream<>(
                MessageStream.fromItems(message("one"), message("two")));

        StepVerifier.create(FluxUtils.of(source))
                    .expectNextCount(2)
                    .verifyComplete();

        assertThat(source.timesClosed())
                .as("source must be closed when the stream completes")
                .isEqualTo(1);
    }

    @Test
    void closesSourceWhenStreamFails() {
        CloseTrackingMessageStream<Message> source = new CloseTrackingMessageStream<>(
                MessageStream.failed(new RuntimeException("oops")));

        StepVerifier.create(FluxUtils.of(source))
                    .expectErrorMessage("oops")
                    .verify();

        assertThat(source.timesClosed())
                .as("source must be closed when the stream fails")
                .isEqualTo(1);
    }

    @Test
    void closesSourceWhenSubscriptionIsCancelled() {
        CloseTrackingMessageStream<Message> source = new CloseTrackingMessageStream<>(
                MessageStream.fromItems(message("one")));

        StepVerifier.create(FluxUtils.of(source), 0)
                    .thenCancel()
                    .verify();

        assertThat(source.timesClosed())
                .as("source must be closed when the subscription is cancelled")
                .isEqualTo(1);
    }

    @Test
    void closesSourceOnceWhenProcessingThrows() {
        CloseTrackingMessageStream<Message> source = new CloseTrackingMessageStream<>(new FailingMessageStream<>());

        StepVerifier.create(FluxUtils.of(source))
                    .expectErrorMessage("boom")
                    .verify();

        assertThat(source.timesClosed())
                .as("source must be closed exactly once when processing throws")
                .isEqualTo(1);
    }

    private static Message message(Object payload) {
        return new GenericMessage(new MessageType("test"), payload);
    }

    /**
     * {@link MessageStream} decorator that counts invocations of {@link #close()} while delegating all behavior, so a
     * test can assert the source was closed regardless of which terminal signal triggered it.
     */
    private static class CloseTrackingMessageStream<M extends Message> implements MessageStream<M> {

        private final MessageStream<M> delegate;
        private final AtomicInteger timesClosed = new AtomicInteger();

        private CloseTrackingMessageStream(MessageStream<M> delegate) {
            this.delegate = delegate;
        }

        private int timesClosed() {
            return timesClosed.get();
        }

        @Override
        public Optional<Entry<M>> next() {
            return delegate.next();
        }

        @Override
        public Optional<Entry<M>> peek() {
            return delegate.peek();
        }

        @Override
        public void setCallback(Runnable callback) {
            delegate.setCallback(callback);
        }

        @Override
        public Optional<Throwable> error() {
            return delegate.error();
        }

        @Override
        public boolean isCompleted() {
            return delegate.isCompleted();
        }

        @Override
        public boolean hasNextAvailable() {
            return delegate.hasNextAvailable();
        }

        @Override
        public void close() {
            timesClosed.incrementAndGet();
            delegate.close();
        }
    }

    /**
     * {@link MessageStream} that throws while being consumed, to exercise the error-handling branch of the
     * {@link FluxUtils.FluxStreamAdapter}.
     */
    private static class FailingMessageStream<M extends Message> implements MessageStream<M> {

        @Override
        public Optional<Entry<M>> next() {
            return Optional.empty();
        }

        @Override
        public Optional<Entry<M>> peek() {
            return Optional.empty();
        }

        @Override
        public void setCallback(Runnable callback) {
            // no-op
        }

        @Override
        public Optional<Throwable> error() {
            return Optional.empty();
        }

        @Override
        public boolean isCompleted() {
            return false;
        }

        @Override
        public boolean hasNextAvailable() {
            throw new RuntimeException("boom");
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
