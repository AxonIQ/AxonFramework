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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link MonoUtils}.
 * <p>
 * Guards that {@link MonoUtils#asSingle(Mono)} subscribes the given {@link Mono} with ThreadLocal context capture
 * ({@link Mono#contextCapture()}). {@link Mono#toFuture()} subscribes with a context-less subscriber, so without the
 * explicit capture the mono's Reactor {@code Context} would always be empty and thread-bound state present at
 * subscription -- such as the tracing span a message handler runs under -- could never reach the mono's operators or
 * context-reading instrumentation downstream. These tests register a plain test {@code ThreadLocal} with Micrometer's
 * {@link ContextRegistry} and assert its value is captured into the Reactor {@link ContextView}, acting as the
 * regression tripwire for that capture.
 *
 * @author Mateusz Nowak
 */
class MonoUtilsTest {

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

    @Nested
    class AsSingle {

        @Test
        void capturesThreadLocalValuesIntoTheReactorContextAtSubscription() throws Exception {
            // given
            THREAD_LOCAL.set("trace-123");
            AtomicReference<ContextView> observedContext = new AtomicReference<>();
            Mono<Message> mono = Mono.deferContextual(ctx -> {
                observedContext.set(ctx);
                return Mono.just(message("payload"));
            });

            // when
            MessageStream.Entry<Message> entry = joinAndUnwrap(
                    MonoUtils.asSingle(mono).asCompletableFuture()
            );

            // then
            assertThat(entry).isNotNull();
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
            Mono<Message> mono = Mono.just(message("payload"))
                                     .delayElement(Duration.ofMillis(10))
                                     .flatMap(message -> Mono.deferContextual(ctx -> {
                                         observedContext.set(ctx);
                                         observedThread.set(Thread.currentThread().getName());
                                         return Mono.just(message);
                                     }));

            // when
            joinAndUnwrap(MonoUtils.asSingle(mono).asCompletableFuture());

            // then
            assertThat(observedThread.get())
                    .as("the downstream operator must have run on another thread for this test to be meaningful")
                    .isNotEqualTo(subscribingThread);
            assertThat(observedContext.get().<String>getOrDefault(THREAD_LOCAL_KEY, null))
                    .as("the captured context must travel structurally with the pipeline, not with the thread")
                    .isEqualTo("trace-456");
        }

        @Test
        void captureIsASnapshotTakenAtSubscriptionNotAtEmission() throws Exception {
            // given
            THREAD_LOCAL.set("at-subscription");
            AtomicReference<ContextView> observedContext = new AtomicReference<>();
            Sinks.One<Message> sink = Sinks.one();
            Mono<Message> mono = sink.asMono().flatMap(message -> Mono.deferContextual(ctx -> {
                observedContext.set(ctx);
                return Mono.just(message);
            }));

            // when subscription happens now, emission only after the ThreadLocal changed
            CompletableFuture<? extends MessageStream.Entry<Message>> result =
                    MonoUtils.asSingle(mono).asCompletableFuture();
            THREAD_LOCAL.set("changed-after-subscription");
            sink.tryEmitValue(message("payload"));
            joinAndUnwrap(result);

            // then
            assertThat(observedContext.get().<String>getOrDefault(THREAD_LOCAL_KEY, null))
                    .as("the capture must snapshot the ThreadLocal at subscription time")
                    .isEqualTo("at-subscription");
        }
    }

    @Nested
    class AsSingleWithContextSupplier {

        @Test
        void capturesThreadLocalValuesIntoTheReactorContextAtSubscription() throws Exception {
            // given
            THREAD_LOCAL.set("trace-789");
            AtomicReference<ContextView> observedContext = new AtomicReference<>();
            Mono<Message> mono = Mono.deferContextual(ctx -> {
                observedContext.set(ctx);
                return Mono.just(message("payload"));
            });

            // when
            MessageStream.Entry<Message> entry = joinAndUnwrap(
                    MonoUtils.asSingle(mono, message -> Context.empty()).asCompletableFuture()
            );

            // then
            assertThat(entry).isNotNull();
            assertThat(observedContext.get().<String>getOrDefault(THREAD_LOCAL_KEY, null))
                    .as("the ThreadLocal value current at subscription must be captured into the Reactor Context")
                    .isEqualTo("trace-789");
        }
    }

    private static Message message(Object payload) {
        return new GenericMessage(new MessageType("test"), payload);
    }
}
