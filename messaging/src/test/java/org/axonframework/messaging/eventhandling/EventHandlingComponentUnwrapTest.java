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

package org.axonframework.messaging.eventhandling;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequenceCachingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequenceOverridingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequencingEventHandlingComponent;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the general {@code unwrap(Class)} capability convention on {@link EventHandlingComponent} and
 * its forwarding through {@link DelegatingEventHandlingComponent} decorators. The capability resolved here is an
 * arbitrary marker type ({@link SampleCapability}); the mechanism is not tied to any specific capability.
 *
 * @author Allard Buijze
 */
class EventHandlingComponentUnwrapTest {

    /**
     * An arbitrary capability type, standing in for any interface a component might expose through {@code unwrap}.
     */
    private interface SampleCapability {

        String capabilityName();
    }

    @Nested
    class PlainComponent {

        @Test
        void unwrapResolvesItselfButNotAnUnrelatedCapability() {
            EventHandlingComponent component = new PlainEventHandlingComponent();

            assertThat(component.unwrap(EventHandlingComponent.class)).containsSame(component);
            assertThat(component.unwrap(SampleCapability.class)).isEmpty();
        }
    }

    @Nested
    class DecoratedComponent {

        @Test
        void unwrapFindsCapabilityThroughTheDecoratorChain() {
            CapabilityComponent capability = new CapabilityComponent();
            EventHandlingComponent decorated =
                    new SequencingEventHandlingComponent(new SequenceCachingEventHandlingComponent(capability));

            assertThat(decorated.unwrap(SampleCapability.class)).containsSame(capability);
        }

        @Test
        void unwrapReturnsEmptyForPlainComponentThroughTheDecoratorChain() {
            EventHandlingComponent decorated =
                    new SequencingEventHandlingComponent(
                            new SequenceCachingEventHandlingComponent(new PlainEventHandlingComponent()));

            assertThat(decorated.unwrap(SampleCapability.class)).isEmpty();
        }

        // SequenceOverridingEventHandlingComponent is the one decorator with a hand-written (not inherited) unwrap.
        @Test
        void unwrapFindsCapabilityThroughTheSequenceOverridingDecorator() {
            CapabilityComponent capability = new CapabilityComponent();
            EventHandlingComponent decorated = new SequenceOverridingEventHandlingComponent(
                    (event, context) -> Optional.of(event.identifier()), capability);

            assertThat(decorated.unwrap(SampleCapability.class)).containsSame(capability);
        }
    }

    @Nested
    class AnnotatedComponent {

        @Test
        void unwrapFindsCapabilityOnAnAnnotatedHandlerThatImplementsIt() {
            CapabilityProjection projection = new CapabilityProjection();
            EventHandlingComponent component = annotatedComponent(projection);

            assertThat(component.unwrap(SampleCapability.class)).containsSame(projection);
        }

        @Test
        void unwrapReturnsEmptyForAnAnnotatedHandlerThatDoesNotImplementTheCapability() {
            EventHandlingComponent component = annotatedComponent(new PlainProjection());

            assertThat(component.unwrap(SampleCapability.class)).isEmpty();
        }

        private static EventHandlingComponent annotatedComponent(Object eventHandler) {
            return new AnnotatedEventHandlingComponent<>(
                    eventHandler,
                    ClasspathParameterResolverFactory.forClass(eventHandler.getClass()),
                    ClasspathHandlerDefinition.forClass(eventHandler.getClass()),
                    new AnnotationMessageTypeResolver(),
                    new DelegatingEventConverter(PassThroughConverter.INSTANCE)
            );
        }
    }

    @SuppressWarnings("unused")
    private static class PlainProjection {

        @EventHandler
        void on(String event) {
            // no-op handler
        }
    }

    // An annotated projection POJO that also exposes a capability: the headline detection path.
    private static class CapabilityProjection extends PlainProjection implements SampleCapability {

        @Override
        public String capabilityName() {
            return "projection";
        }
    }

    private static class PlainEventHandlingComponent implements EventHandlingComponent {

        @Override
        public MessageStream.Empty<Message> handle(@NonNull EventMessage event, @NonNull ProcessingContext context) {
            return MessageStream.empty();
        }

        @Override
        public Set<QualifiedName> supportedEvents() {
            return Set.of();
        }

        @Override
        public Object sequenceIdentifierFor(@NonNull EventMessage event, @NonNull ProcessingContext context) {
            return event.identifier();
        }

        @Override
        public void describeTo(@NonNull ComponentDescriptor descriptor) {
            descriptor.describeProperty("type", getClass().getSimpleName());
        }
    }

    private static class CapabilityComponent extends PlainEventHandlingComponent implements SampleCapability {

        @Override
        public String capabilityName() {
            return "component";
        }
    }
}
