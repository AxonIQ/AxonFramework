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

package org.axonframework.messaging.eventhandling.processing.streaming.checkpoint;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.junit.jupiter.api.AfterEach;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end test proving that an ordinary annotated event handler that {@code implements Checkpointing} works through a
 * real {@code PooledStreamingEventProcessor}: it is detected as a self-checkpointing participant, receives its
 * {@link CheckpointTrigger} (here through a handler parameter), defers progress until it confirms durability, and has
 * the stored {@link TrackingToken} advanced only on its request -- with the final position flushed on release.
 *
 * @author Allard Buijze
 */
class AnnotatedCheckpointingProjectionTest {

    private static final String PROCESSOR_NAME = "async-projection";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Test
    void storedTokenAdvancesOnlyWhenTheAnnotatedProjectionRequestsACheckpoint() {
        // given -- a single annotated projection that implements Checkpointing -> the processor is fully-deferred
        AsyncInMemoryStreamableEventSource eventSource = new AsyncInMemoryStreamableEventSource();
        InMemoryTokenStore tokenStore = new InMemoryTokenStore();
        AsyncProjection projection = new AsyncProjection();

        var module =
                EventProcessorModule.pooledStreaming(PROCESSOR_NAME)
                                    .eventHandlingComponents(components -> components.autodetected(
                                            "projection", cfg -> projection
                                    ))
                                    .customized((cfg, c) -> c.eventSource(eventSource)
                                                             .tokenStore(tokenStore)
                                                             .initialSegmentCount(1));

        configuration = MessagingConfigurer.create()
                                           .eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(module)))
                                           .build();
        configuration.start();

        // when -- three events are published and all handled
        eventSource.publishMessage(EventTestUtils.asEventMessage("event-0"));
        eventSource.publishMessage(EventTestUtils.asEventMessage("event-1"));
        eventSource.publishMessage(EventTestUtils.asEventMessage("event-2"));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(projection.handled).hasSize(3));
        long handledPosition = projection.lastHandled.position().orElseThrow();

        // then -- the projection has not requested a checkpoint, so the stored token has NOT caught up to what was handled
        await().during(Duration.ofMillis(200)).atMost(TIMEOUT)
               .untilAsserted(() -> assertThat(positionOf(tokenStore)).isLessThan(handledPosition));

        // when -- the projection confirms its work durable and requests a checkpoint covering everything handled
        projection.flush();

        // then -- the stored token advances to the last handled position
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(positionOf(tokenStore)).isEqualTo(handledPosition));

        // when -- the processor shuts down
        configuration.shutdown();
        configuration = null;

        // then -- the segment was released through the checkpoint protocol
        assertThat(projection.released).isNotEmpty();
    }

    private static long positionOf(TokenStore tokenStore) {
        TrackingToken token = FutureUtils.joinAndUnwrap(tokenStore.fetchToken(PROCESSOR_NAME, 0, null));
        return token == null ? -1L : token.position().orElse(-1L);
    }

    /**
     * A plain projection: it carries {@link EventHandler} methods and {@code implements Checkpointing}, declaring a
     * {@link CheckpointTrigger} parameter (resolved by the parameter resolver) rather than retaining the trigger from
     * {@link #onSegmentClaimed}. It only requests a checkpoint when explicitly {@link #flush() flushed}, modelling an
     * asynchronous projection that confirms durability out of band.
     */
    static class AsyncProjection implements Checkpointing {

        private final List<String> handled = new CopyOnWriteArrayList<>();
        private final List<Segment> released = new CopyOnWriteArrayList<>();
        private volatile CheckpointTrigger trigger;
        private volatile TrackingToken lastHandled;

        @EventHandler
        void on(String event, TrackingToken token, CheckpointTrigger checkpoint) {
            this.trigger = checkpoint;
            this.lastHandled = token;
            handled.add(event);
        }

        void flush() {
            trigger.requestCheckpoint(lastHandled);
        }

        // onSegmentClaimed uses the Checkpointing default (the trigger arrives through the handler parameter).
        @Override
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            return CompletableFuture.completedFuture(lastHandled);
        }

        @Override
        public CompletableFuture<TrackingToken> onSegmentReleased(@NonNull Segment segment,
                                                                  @NonNull TrackingToken upTo) {
            released.add(segment);
            return CompletableFuture.completedFuture(lastHandled == null ? upTo : lastHandled);
        }
    }
}
