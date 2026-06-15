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
package org.axonframework.messaging.queryhandling;

import org.axonframework.common.lifecycle.ShutdownInProgressException;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QueueMessageStream;
import org.junit.jupiter.api.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating {@link QueryShutdownManager}.
 */
class QueryShutdownManagerTest {

    private static final MessageType RESPONSE_TYPE = new MessageType("response");

    @Nested
    class WhenTrackingMessageStream {

        @Test
        void trackedStreamPassesThroughMessages() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            QueueMessageStream<QueryResponseMessage> source =
                    new QueueMessageStream<>(new ArrayBlockingQueue<>(8));
            source.offer(new GenericQueryResponseMessage(RESPONSE_TYPE, "answer", String.class),
                         Context.empty());
            source.seal();

            // when
            MessageStream<QueryResponseMessage> tracked = manager.track(source);

            // then
            StepVerifier.create(FluxUtils.of(tracked).map(e -> e.message().payloadAs(String.class)))
                        .expectNext("answer")
                        .verifyComplete();
        }

        @Test
        void deregistersAutomaticallyWhenStreamCompletesNaturally() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            QueueMessageStream<QueryResponseMessage> source =
                    new QueueMessageStream<>(new ArrayBlockingQueue<>(8));
            MessageStream<QueryResponseMessage> tracked = manager.track(source);

            // when — seal the source and consume it so onClose fires
            source.seal();
            FluxUtils.of(tracked).blockLast(Duration.ofSeconds(1));

            // then — nothing remains to close, shutdown completes immediately
            assertThat(manager.shutdown()).isCompleted();
        }

        @Test
        void closesStreamOnImmediateShutdown() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            QueueMessageStream<QueryResponseMessage> source =
                    new QueueMessageStream<>(new ArrayBlockingQueue<>(8));
            AtomicBoolean closedByManager = new AtomicBoolean(false);
            MessageStream<QueryResponseMessage> tracked = manager.track(
                    source.onClose(() -> closedByManager.set(true))
            );

            // when
            manager.shutdown();

            // then
            assertThat(closedByManager).isTrue();
            assertThat(tracked.isCompleted()).isTrue();
        }
    }

    @Nested
    class WhenTrackingPublisher {

        @Test
        void trackedPublisherPassesThroughElements() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

            // when
            Publisher<String> tracked = manager.track(sink.asFlux());

            // then
            StepVerifier.create(tracked)
                        .then(() -> sink.tryEmitNext("hello"))
                        .expectNext("hello")
                        .then(sink::tryEmitComplete)
                        .verifyComplete();
        }

        @Test
        void deregistersAutomaticallyWhenPublisherCompletesNaturally() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            Publisher<String> tracked = manager.track(sink.asFlux());

            // when — complete the publisher naturally and consume it
            StepVerifier.create(tracked)
                        .then(sink::tryEmitComplete)
                        .verifyComplete();

            // then — nothing remains to close, shutdown completes immediately
            assertThat(manager.shutdown()).isCompleted();
        }

        @Test
        void emitsShutdownExceptionToSubscriberOnImmediateShutdown() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            Publisher<String> tracked = manager.track(sink.asFlux());

            // when / then
            StepVerifier.create(tracked)
                        .then(() -> sink.tryEmitNext("first"))
                        .expectNext("first")
                        .then(manager::shutdown)
                        .expectErrorSatisfies(e -> assertThat(e)
                                .isInstanceOf(ShutdownInProgressException.class)
                                .hasMessageContaining("shutting down"))
                        .verify(Duration.ofSeconds(2));
        }
    }

    @Nested
    class WhenShuttingDownImmediately {

        @Test
        void returnsAlreadyCompletedFutureWhenNothingIsTracked() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();

            // when
            CompletableFuture<Void> result = manager.shutdown();

            // then
            assertThat(result).isCompleted();
        }

        @Test
        void closesAllActiveMessageStreams() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            QueueMessageStream<QueryResponseMessage> source1 =
                    new QueueMessageStream<>(new ArrayBlockingQueue<>(8));
            QueueMessageStream<QueryResponseMessage> source2 =
                    new QueueMessageStream<>(new ArrayBlockingQueue<>(8));
            MessageStream<QueryResponseMessage> tracked1 = manager.track(source1);
            MessageStream<QueryResponseMessage> tracked2 = manager.track(source2);

            // when
            manager.shutdown();

            // then
            assertThat(tracked1.isCompleted()).isTrue();
            assertThat(tracked2.isCompleted()).isTrue();
        }

        @Test
        void signalsShutdownErrorToAllActivePublisherSubscribers() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.closeImmediately();
            Sinks.Many<String> sink1 = Sinks.many().unicast().onBackpressureBuffer();
            Sinks.Many<String> sink2 = Sinks.many().unicast().onBackpressureBuffer();
            Publisher<String> tracked1 = manager.track(sink1.asFlux());
            Publisher<String> tracked2 = manager.track(sink2.asFlux());

            // when / then
            StepVerifier.create(tracked1)
                        .then(manager::shutdown)
                        .expectError(ShutdownInProgressException.class)
                        .verify(Duration.ofSeconds(2));

            StepVerifier.create(tracked2)
                        .expectError(ShutdownInProgressException.class)
                        .verify(Duration.ofSeconds(2));
        }
    }

    @Nested
    class WhenShuttingDownWithGracePeriod {

        @Test
        void returnsAlreadyCompletedFutureWhenNothingIsTracked() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.withGracePeriod(Duration.ofSeconds(1));

            // when
            CompletableFuture<Void> result = manager.shutdown();

            // then
            assertThat(result).isCompleted();
        }

        @Test
        void completesWithoutForceClosingWhenPublisherFinishesWithinGracePeriod() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.withGracePeriod(Duration.ofSeconds(2));
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            Publisher<String> tracked = manager.track(sink.asFlux());

            // start consuming
            CompletableFuture<Void> consumed = Flux.from(tracked).then().toFuture();

            // when
            CompletableFuture<Void> shutdown = manager.shutdown();
            sink.tryEmitComplete(); // completes naturally within grace period

            // then
            await().atMost(Duration.ofSeconds(3))
                   .untilAsserted(() -> {
                       assertThat(consumed).isCompleted();
                       assertThat(shutdown).isCompleted();
                   });
        }

        @Test
        void forceClosesRemainingStreamsAfterGracePeriodExpires() {
            // given
            QueryShutdownManager manager = QueryShutdownManager.withGracePeriod(Duration.ofMillis(100));
            QueueMessageStream<QueryResponseMessage> source =
                    new QueueMessageStream<>(new ArrayBlockingQueue<>(8));
            MessageStream<QueryResponseMessage> tracked = manager.track(source);

            // when — stream never completes naturally; grace period must expire and force-close it
            CompletableFuture<Void> shutdown = manager.shutdown();

            // then
            await().atMost(Duration.ofSeconds(2))
                   .untilAsserted(() -> {
                       assertThat(shutdown).isCompleted();
                       assertThat(tracked.isCompleted()).isTrue();
                   });
        }
    }
}
