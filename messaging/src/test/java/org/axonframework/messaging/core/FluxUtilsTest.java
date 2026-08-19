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

import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link FluxUtils}.
 * <p>
 * For {@link FluxUtils#of(MessageStream)}: guards that the source {@link MessageStream} is
 * {@link MessageStream#close() closed} on <em>every</em> terminal signal of the resulting
 * {@link reactor.core.publisher.Flux Flux} -- completion, error, and cancellation -- so resources held by the source
 * (e.g. a subscription query registration) are always released. The completion case is a regression guard: cleanup
 * used to be wired to cancellation only, so a normally-completing stream leaked its source.
 * <p>
 * For {@link FluxUtils#asMessageStream(Flux)}: guards that the given {@link Flux} is subscribed with ThreadLocal
 * context capture ({@link Flux#contextCapture()}). The resulting stream subscribes the flux with a plain,
 * context-less subscriber, so without the explicit capture the flux's Reactor {@code Context} would always be empty
 * and thread-bound state present at subscription -- such as the tracing span a message handler runs under -- could
 * never reach the flux's operators or context-reading instrumentation downstream. Those tests register a plain test
 * {@code ThreadLocal} with Micrometer's {@link ContextRegistry} and assert its value is captured into the Reactor
 * {@link ContextView}, acting as the regression tripwire for that capture.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
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

    @Nested
    class AsMessageStream {

        private static final String THREAD_LOCAL_KEY = "test.correlation";
        private static final ThreadLocal<String> THREAD_LOCAL = new ThreadLocal<>();

        @BeforeEach
        void setUp() {
            ContextRegistry.getInstance()
                           .registerThreadLocalAccessor(THREAD_LOCAL_KEY,
                                                        THREAD_LOCAL::get,
                                                        THREAD_LOCAL::set,
                                                        THREAD_LOCAL::remove);
        }

        @AfterEach
        void tearDown() {
            THREAD_LOCAL.remove();
            ContextRegistry.getInstance().removeThreadLocalAccessor(THREAD_LOCAL_KEY);
        }

        @Test
        void capturesThreadLocalValuesIntoTheReactorContextAtSubscription() throws Exception {
            // given
            THREAD_LOCAL.set("trace-123");
            AtomicReference<ContextView> observedContext = new AtomicReference<>();
            Flux<Message> flux = Flux.deferContextual(ctx -> {
                observedContext.set(ctx);
                return Flux.just(message("one"), message("two"));
            });

            // when
            List<Message> messages = joinAndUnwrap(
                    FluxUtils.asMessageStream(flux)
                             .collect(ArrayList<Message>::new, List::add)
            );

            // then
            assertThat(messages).hasSize(2);
            assertThat(observedContext.get().<String>getOrDefault(THREAD_LOCAL_KEY, null))
                    .as("the ThreadLocal value current at subscription must be captured into the Reactor Context")
                    .isEqualTo("trace-123");
        }

        @Test
        void capturedContextIsVisibleToOperatorsRunningOnOtherThreads() throws Exception {
            // given
            THREAD_LOCAL.set("trace-456");
            String subscribingThread = Thread.currentThread().getName();
            AtomicReference<ContextView> observedContext = new AtomicReference<>();
            AtomicReference<String> observedThread = new AtomicReference<>();
            Flux<Message> flux = Flux.just(message("one"))
                                     .publishOn(Schedulers.boundedElastic())
                                     .concatMap(message -> Mono.deferContextual(ctx -> {
                                         observedContext.set(ctx);
                                         observedThread.set(Thread.currentThread().getName());
                                         return Mono.just(message);
                                     }));

            // when
            List<Message> messages = joinAndUnwrap(
                    FluxUtils.asMessageStream(flux)
                             .collect(ArrayList<Message>::new, List::add)
            );

            // then
            assertThat(messages).hasSize(1);
            assertThat(observedThread.get())
                    .as("the downstream operator must have run on another thread for this test to be meaningful")
                    .isNotEqualTo(subscribingThread);
            assertThat(observedContext.get().<String>getOrDefault(THREAD_LOCAL_KEY, null))
                    .as("the captured context must travel structurally with the pipeline, not with the thread")
                    .isEqualTo("trace-456");
        }
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
