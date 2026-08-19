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

package org.axonframework.eventsourcing.tracing;

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.configuration.MessagingTracingSettings;
import org.axonframework.messaging.tracing.annotation.TracingHandlerEnhancerDefinition;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code @EventSourcingHandler} suppression of {@link TracingHandlerEnhancerDefinition} against the
 * <em>real</em> {@link EventSourcingHandler} annotation, routed through the real annotation pipeline
 * ({@link AnnotatedMessageHandlingMemberDefinition}). This proves the {@code EventSourcingHandler.payloadType}
 * handler-attribute key the enhancer detects by name actually matches what the pipeline produces - the stub-based
 * suppression tests live with the enhancer in {@code axon-messaging}.
 */
class EventSourcingHandlerTracingTest {

    private TestSpanFactory spanFactory;
    private TracingHandlerEnhancerDefinition enhancer;
    private MessageHandlingMember<FixtureEntity> member;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        spanFactory = new TestSpanFactory();
        enhancer = new TracingHandlerEnhancerDefinition();
        Method handler = FixtureEntity.class.getDeclaredMethod("on", String.class);
        member = new AnnotatedMessageHandlingMemberDefinition()
                .createHandler(FixtureEntity.class,
                               handler,
                               ClasspathParameterResolverFactory.forClass(FixtureEntity.class),
                               result -> MessageStream.empty())
                .orElseThrow(() -> new IllegalStateException("Pipeline did not recognise @EventSourcingHandler"));
    }

    @Test
    void suppressesARealEventSourcingHandlerByDefault() {
        // given a context with a SpanFactory but default (absent) settings - eventSourcingHandlersEnabled=false
        ProcessingContext context = StubProcessingContext.withComponents(
                registry -> registry.registerComponent(SpanFactory.class, c -> spanFactory)
        );
        EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "payload");
        FixtureEntity entity = new FixtureEntity();

        // when
        MessageHandlingMember<FixtureEntity> wrapped = enhancer.wrapHandler(member);
        wrapped.handle(event, context, entity);

        // then the replayed event still evolves the entity, but no span is opened
        assertThat(entity.handled).isTrue();
        spanFactory.verifyNoSpan("FixtureEntity.on(String)");
    }

    @Test
    void tracesARealEventSourcingHandlerWhenEventSourcingHandlersEnabled() {
        // given a context whose settings enable eventSourcingHandlersEnabled
        MessagingTracingSettings showHandlers =
                MessagingTracingSettings.enabledByDefault().withEventSourcingHandlersEnabled(true);
        ProcessingContext context = StubProcessingContext.withComponents(
                registry -> {
                    registry.registerComponent(SpanFactory.class, c -> spanFactory);
                    registry.registerComponent(MessagingTracingSettings.class, c -> showHandlers);
                }
        );
        EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "payload");
        FixtureEntity entity = new FixtureEntity();

        // when
        MessageHandlingMember<FixtureEntity> wrapped = enhancer.wrapHandler(member);
        wrapped.handle(event, context, entity);

        // then the handler ran and its method span opened AND closed around the (synchronous) invocation --
        // branch-scoped spans end on their own invocation's termination, not at the enclosing context's end
        assertThat(entity.handled).isTrue();
        spanFactory.verifySpanCompleted("FixtureEntity.on(String)");
    }

    @SuppressWarnings("unused")
    private static final class FixtureEntity {

        private boolean handled = false;

        @EventSourcingHandler
        void on(String event) {
            this.handled = true;
        }
    }
}
