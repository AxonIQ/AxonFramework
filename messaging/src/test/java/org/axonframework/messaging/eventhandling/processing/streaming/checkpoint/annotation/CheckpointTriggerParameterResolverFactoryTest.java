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

package org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.annotation;

import org.axonframework.conversion.Converter;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link CheckpointTriggerParameterResolverFactory}: a handler method may declare a
 * {@link CheckpointTrigger} parameter, which is resolved from the active {@link ProcessingContext} (where a pooled
 * streaming event processor places it for a self-checkpointing component).
 *
 * @author Allard Buijze
 */
class CheckpointTriggerParameterResolverFactoryTest {

    private RecordingHandler handler;
    private EventHandlingComponent testSubject;

    @BeforeEach
    void setUp() {
        handler = new RecordingHandler();
        Converter converter = PassThroughConverter.INSTANCE;
        testSubject = new AnnotatedEventHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(RecordingHandler.class),
                ClasspathHandlerDefinition.forClass(RecordingHandler.class),
                new AnnotationMessageTypeResolver(),
                new DelegatingEventConverter(converter)
        );
    }

    @Nested
    class Factory {

        @Test
        void createsAResolverOnlyForACheckpointTriggerParameter() throws NoSuchMethodException {
            CheckpointTriggerParameterResolverFactory factory = new CheckpointTriggerParameterResolverFactory();
            Method method = RecordingHandler.class.getDeclaredMethod("on", String.class, CheckpointTrigger.class);

            ParameterResolver<?> triggerResolver = factory.createInstance(method, method.getParameters(), 1);
            ParameterResolver<?> payloadResolver = factory.createInstance(method, method.getParameters(), 0);

            assertThat(triggerResolver).isNotNull();
            assertThat(payloadResolver).isNull();
        }

        @Test
        void resolverMatchesRegardlessOfWhetherATriggerIsPresent() throws NoSuchMethodException {
            CheckpointTriggerParameterResolverFactory factory = new CheckpointTriggerParameterResolverFactory();
            Method method = RecordingHandler.class.getDeclaredMethod("on", String.class, CheckpointTrigger.class);
            ParameterResolver<?> resolver = factory.createInstance(method, method.getParameters(), 1);

            // Matches unconditionally so the handler is never silently skipped; absence is reported at resolution time.
            RecordingCheckpointTrigger trigger = new RecordingCheckpointTrigger();
            assertThat(resolver.matches(contextWith(trigger))).isTrue();
            assertThat(resolver.matches(contextWithout())).isTrue();
        }

        @Test
        void resolvingFailsWhenNoTriggerIsPresent() throws NoSuchMethodException {
            CheckpointTriggerParameterResolverFactory factory = new CheckpointTriggerParameterResolverFactory();
            Method method = RecordingHandler.class.getDeclaredMethod("on", String.class, CheckpointTrigger.class);
            ParameterResolver<?> resolver = factory.createInstance(method, method.getParameters(), 1);

            assertThat(resolver.resolveParameterValue(contextWithout()))
                    .failsWithin(Duration.ofSeconds(1))
                    .withThrowableThat()
                    .withCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void isDiscoveredViaServiceLoaderOnTheClasspath() throws NoSuchMethodException {
            // given -- the factory resolved purely through the META-INF/services registration (catches a typo there)
            ParameterResolverFactory classpathFactory =
                    ClasspathParameterResolverFactory.forClass(CheckpointTriggerParameterResolverFactoryTest.class);
            Method method = RecordingHandler.class.getDeclaredMethod("on", String.class, CheckpointTrigger.class);

            // when / then -- the classpath factory creates a resolver for the CheckpointTrigger parameter
            assertThat(classpathFactory.createInstance(method, method.getParameters(), 1)).isNotNull();
        }
    }

    @Nested
    class EndToEnd {

        @Test
        void aHandlerReceivesTheTriggerFromTheContextAndCanRequestACheckpoint() {
            // given
            RecordingCheckpointTrigger trigger = new RecordingCheckpointTrigger();
            EventMessage event = new GenericEventMessage(new MessageType(String.class), "payload");

            // when
            testSubject.handle(event, contextWith(trigger));

            // then -- the trigger was injected and the handler used it to request a checkpoint
            assertThat(handler.receivedTrigger).isSameAs(trigger);
            assertThat(trigger.requested).containsExactly(new GlobalSequenceTrackingToken(7L));
        }

        @Test
        void handlingFailsLoudlyWhenNoTriggerIsAvailable() {
            // given -- a processor that cannot checkpoint places no trigger in the context
            EventMessage event = new GenericEventMessage(new MessageType(String.class), "payload");

            // when -- the handler is matched and invoked, but the CheckpointTrigger parameter cannot be resolved
            var result = testSubject.handle(event, contextWithout()).first().asCompletableFuture();

            // then -- it fails loudly (rather than silently skipping the event) and the handler body never ran
            assertThat(result)
                    .failsWithin(Duration.ofSeconds(1))
                    .withThrowableThat()
                    .withCauseInstanceOf(IllegalStateException.class);
            assertThat(handler.receivedTrigger).isNull();
        }
    }

    private static ProcessingContext contextWith(CheckpointTrigger trigger) {
        return baseContext().withResource(CheckpointTrigger.RESOURCE_KEY, trigger);
    }

    private static ProcessingContext contextWithout() {
        return baseContext();
    }

    private static ProcessingContext baseContext() {
        EventMessage event = new GenericEventMessage(new MessageType(String.class), "payload");
        return StubProcessingContext.withComponent(Converter.class, PassThroughConverter.INSTANCE)
                                    .withMessage(event)
                                    .withResource(TrackingToken.RESOURCE_KEY, new GlobalSequenceTrackingToken(7L));
    }

    @SuppressWarnings("unused")
    private static class RecordingHandler {

        private CheckpointTrigger receivedTrigger;

        @EventHandler
        void on(String event, CheckpointTrigger trigger) {
            this.receivedTrigger = trigger;
            trigger.requestCheckpoint(new GlobalSequenceTrackingToken(7L));
        }
    }

    private static class RecordingCheckpointTrigger implements CheckpointTrigger {

        private final List<TrackingToken> requested = new ArrayList<>();

        @Override
        public void requestCheckpoint() {
            requested.add(null);
        }

        @Override
        public void requestCheckpoint(@NonNull TrackingToken token) {
            requested.add(token);
        }
    }
}
