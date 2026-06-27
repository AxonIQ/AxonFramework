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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating that the {@link Checkpointing} self-checkpointing protocol is <em>inert</em> in a
 * {@link org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor}: a subscribing
 * processor has no tracking token or segments, so it never invokes the lifecycle callbacks and never exposes a
 * {@link CheckpointTrigger}. A {@link Checkpointing} component therefore runs there as a plain event handler, and a
 * {@link CheckpointTrigger} handler parameter fails loudly rather than silently misbehaving.
 *
 * @author Allard Buijze
 */
class CheckpointingInSubscribingProcessorTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    @Test
    void aCheckpointingComponentRunsAsAPlainHandlerWithoutAnyCheckpointCallbacksOrTrigger() {
        // given -- a Checkpointing component registered in a subscribing processor
        SimpleEventBus eventBus = new SimpleEventBus();
        InertCheckpointingProjection projection = new InertCheckpointingProjection();
        AxonConfiguration configuration = subscribingProcessorWith("checkpointing", eventBus, projection);
        configuration.start();

        // when -- an event is published and handled
        EventMessage event = EventTestUtils.asEventMessage("event");
        FutureUtils.joinAndUnwrap(eventBus.publish(null, event));

        // then -- the event is handled, but none of the checkpoint lifecycle callbacks fire and no trigger is exposed:
        // the self-checkpointing behaviour is inert in a (non-streaming) subscribing processor
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(projection.handled).hasValue("event"));
        assertThat(projection.triggerPresentDuringHandling).hasValue(Boolean.FALSE);
        assertThat(projection.segmentClaimed).isFalse();
        assertThat(projection.checkpointAdvanced).isFalse();
        assertThat(projection.segmentReleased).isFalse();

        // cleanup
        configuration.shutdown();
    }

    @Test
    void aHandlerDeclaringACheckpointTriggerParameterFailsLoudlyInASubscribingProcessor() {
        // given -- a handler declaring a CheckpointTrigger parameter, in a subscribing processor that exposes none
        SimpleEventBus eventBus = new SimpleEventBus();
        TriggerParameterHandler handler = new TriggerParameterHandler();
        AxonConfiguration configuration = subscribingProcessorWith("trigger-parameter", eventBus, handler);
        configuration.start();

        // when / then -- publishing fails loudly (the parameter cannot be resolved) rather than silently skipping the
        // event, and the handler body never runs
        EventMessage event = EventTestUtils.asEventMessage("event");
        assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(eventBus.publish(null, event)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(handler.handled).hasValue(null);

        // cleanup
        configuration.shutdown();
    }

    private static AxonConfiguration subscribingProcessorWith(String name, SimpleEventBus eventBus, Object component) {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        configurer.eventProcessing(
                ep -> ep.subscribing(
                        sp -> sp.defaults(d -> d.eventSource(eventBus).errorHandler(PropagatingErrorHandler.instance()))
                                .defaultProcessor(name,
                                                  components -> components.autodetected("component", cfg -> component))
                )
        );
        return configurer.build();
    }

    @SuppressWarnings("unused")
    private static class InertCheckpointingProjection implements Checkpointing {

        private final AtomicReference<String> handled = new AtomicReference<>();
        private final AtomicReference<Boolean> triggerPresentDuringHandling = new AtomicReference<>();
        private final AtomicBoolean segmentClaimed = new AtomicBoolean();
        private final AtomicBoolean checkpointAdvanced = new AtomicBoolean();
        private final AtomicBoolean segmentReleased = new AtomicBoolean();

        @EventHandler
        void on(String event, ProcessingContext context) {
            handled.set(event);
            triggerPresentDuringHandling.set(CheckpointTrigger.fromContext(context).isPresent());
        }

        @Override
        public void onSegmentClaimed(@NonNull Segment segment, @NonNull CheckpointTrigger trigger) {
            segmentClaimed.set(true);
        }

        @Override
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            checkpointAdvanced.set(true);
            return CompletableFuture.completedFuture(requested);
        }

        @Override
        public CompletableFuture<TrackingToken> onSegmentReleased(@NonNull Segment segment,
                                                                  @NonNull TrackingToken upTo) {
            segmentReleased.set(true);
            return CompletableFuture.completedFuture(upTo);
        }
    }

    @SuppressWarnings("unused")
    private static class TriggerParameterHandler {

        private final AtomicReference<String> handled = new AtomicReference<>();

        @EventHandler
        void on(String event, CheckpointTrigger trigger) {
            handled.set(event);
            trigger.requestCheckpoint();
        }
    }
}
