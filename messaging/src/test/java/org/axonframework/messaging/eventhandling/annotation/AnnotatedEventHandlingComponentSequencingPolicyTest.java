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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.conversion.Converter;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.core.sequencing.FullConcurrencyPolicy;
import org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.sequencing.SequentialPolicy.FULL_SEQUENTIAL_POLICY;

/**
 * Test class validating the {@link AnnotatedEventHandlingComponent} sequencing policy behavior. Verifies that
 * {@code sequenceIdentifierFor} returns correct values based on {@link SequencingPolicy} annotation at both class and
 * method levels.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AnnotatedEventHandlingComponentSequencingPolicyTest {

    public static final String AGGREGATE_TYPE = "test";
    public static final String AGGREGATE_IDENTIFIER = "id";

    @Nested
    class NoSequencingPolicyAnnotations {

        @Test
        void should_use_default_sequencing_when_no_policy_annotation() {
            // given
            var eventHandler = new EventHandlerWithoutPolicy();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(AGGREGATE_IDENTIFIER);
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithoutPolicy {

            @EventHandler
            void handle(String event) {
            }
        }
    }

    @Nested
    class ClassLevelSequencingPolicy {

        @Test
        void should_use_sequential_policy_when_annotated_on_class() {
            // given
            var eventHandler = new SequentialPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @Test
        void should_use_full_concurrency_policy_when_annotated_on_class() {
            // given
            var eventHandler = new FullConcurrencyPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void should_use_metadata_sequencing_policy_when_annotated_on_class() {
            // given
            var eventHandler = new MetadataSequencingPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var metadata = Metadata.with("userId", "user123");
            var event = new GenericEventMessage(new MessageType(String.class), "test-event", metadata);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo("user123");
        }

        @Test
        void should_use_property_sequencing_policy_when_annotated_on_class() {
            // given
            var eventHandler = new PropertySequencingPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var eventPayload = new OrderEvent("order123", "item456");
            var event = EventTestUtils.asEventMessage(eventPayload);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo("order123");
        }

        @Test
        void should_use_sequential_per_aggregate_policy_when_annotated_on_class() {
            // given
            var eventHandler = new SequentialPerAggregatePolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(AGGREGATE_IDENTIFIER);
        }

        @SuppressWarnings({"DefaultAnnotationParam", "unused"})
        @SequencingPolicy(type = SequentialPolicy.class)
        private static class SequentialPolicyEventHandler {

            @EventHandler
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        @SequencingPolicy(type = FullConcurrencyPolicy.class)
        private static class FullConcurrencyPolicyEventHandler {

            @EventHandler
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        @SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = {"userId"})
        private static class MetadataSequencingPolicyEventHandler {

            @EventHandler
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        @SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"orderId"})
        private static class PropertySequencingPolicyEventHandler {

            @EventHandler
            void handle(OrderEvent event) {
            }
        }

        @SuppressWarnings("unused")
        @SequencingPolicy(type = SequentialPerAggregatePolicy.class)
        private static class SequentialPerAggregatePolicyEventHandler {

            @EventHandler
            void handle(String event) {
            }
        }
    }

    @Nested
    class MethodLevelSequencingPolicy {

        @Test
        void should_use_sequential_policy_when_annotated_on_method() {
            // given
            var eventHandler = new MethodLevelSequentialPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @Test
        void should_use_full_concurrency_policy_when_annotated_on_method() {
            // given
            var eventHandler = new MethodLevelFullConcurrencyPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void should_use_metadata_sequencing_policy_when_annotated_on_method() {
            // given
            var eventHandler = new MethodLevelMetadataSequencingPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var metadata = Metadata.with("tenantId", "tenant789");
            var event = new GenericEventMessage(new MessageType(String.class), "test-event", metadata);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo("tenant789");
        }

        @Test
        void should_use_property_sequencing_policy_when_annotated_on_method() {
            // given
            var eventHandler = new MethodLevelPropertySequencingPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var eventPayload = new CustomerEvent("customer456", "John Doe");
            var event = EventTestUtils.asEventMessage(eventPayload);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo("customer456");
        }

        @Test
        void should_use_sequential_per_aggregate_policy_when_annotated_on_method() {
            // given
            var eventHandler = new MethodLevelSequentialPerAggregatePolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(AGGREGATE_IDENTIFIER);
        }

        @SuppressWarnings("unused")
        private static class MethodLevelSequentialPolicyEventHandler {

            @SuppressWarnings("DefaultAnnotationParam")
            @EventHandler
            @SequencingPolicy(type = SequentialPolicy.class)
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        private static class MethodLevelFullConcurrencyPolicyEventHandler {

            @EventHandler
            @SequencingPolicy(type = FullConcurrencyPolicy.class)
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        private static class MethodLevelMetadataSequencingPolicyEventHandler {

            @EventHandler
            @SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = {"tenantId"})
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        private static class MethodLevelPropertySequencingPolicyEventHandler {

            @EventHandler
            @SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"customerId"})
            void handle(CustomerEvent event) {
            }
        }

        @SuppressWarnings("unused")
        private static class MethodLevelSequentialPerAggregatePolicyEventHandler {

            @EventHandler
            @SequencingPolicy(type = SequentialPerAggregatePolicy.class)
            void handle(String event) {
            }
        }
    }

    @Nested
    class MethodOverridesClassPolicy {

        @Test
        void should_prefer_method_policy_over_class_policy() {
            // given
            var eventHandler = new MixedPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void should_use_class_policy_when_method_has_no_policy() {
            // given
            var eventHandler = new MixedPolicyEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var eventPayload = new OrderEvent("order789", "item123");
            var event = EventTestUtils.asEventMessage(eventPayload);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @SuppressWarnings({"unused", "DefaultAnnotationParam"})
        @SequencingPolicy(type = SequentialPolicy.class)
        private static class MixedPolicyEventHandler {

            @EventHandler
            @SequencingPolicy(type = FullConcurrencyPolicy.class)
            void handleStringEvent(String event) {
            }

            @EventHandler
            void handleOrderEvent(OrderEvent event) {
            }
        }
    }

    @Nested
    class MultipleEventHandlers {

        @Test
        void should_apply_different_policies_to_different_handlers() {
            // given
            var eventHandler = new MultipleHandlersEventHandler();
            var component = annotatedEventHandlingComponent(eventHandler);
            var stringEvent = EventTestUtils.asEventMessage("test-event");
            var orderEvent = EventTestUtils.asEventMessage(new OrderEvent("order123", "item456"));
            var context = messageProcessingContext(stringEvent);

            // when
            var stringSequenceId = component.sequenceIdentifierFor(stringEvent, context);
            var orderSequenceId = component.sequenceIdentifierFor(orderEvent, context);

            // then
            assertThat(stringSequenceId).isEqualTo(FULL_SEQUENTIAL_POLICY);
            assertThat(orderSequenceId).isEqualTo(orderEvent.identifier());
        }

        @SuppressWarnings("unused")
        private static class MultipleHandlersEventHandler {

            @SuppressWarnings("DefaultAnnotationParam")
            @EventHandler
            @SequencingPolicy(type = SequentialPolicy.class)
            void handleString(String event) {
            }

            @EventHandler
            @SequencingPolicy(type = FullConcurrencyPolicy.class)
            void handleOrder(OrderEvent event) {
            }
        }
    }

    @Nested
    class ProcessorWideFallbackPolicy {

        private final org.axonframework.messaging.core.sequencing.SequencingPolicy<EventMessage> fallbackPolicy =
                (event, context) -> Optional.of("processor-wide");

        @Test
        void should_use_fallback_policy_when_no_policy_annotation() {
            // given
            var component = annotatedEventHandlingComponent(new EventHandlerWithoutPolicy());
            applyFallbackPolicy(component);
            var event = EventTestUtils.asEventMessage("test-event");

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, messageProcessingContext(event));

            // then
            assertThat(sequenceIdentifier).isEqualTo("processor-wide");
        }

        @Test
        void should_prefer_class_policy_over_fallback_policy() {
            // given
            var component = annotatedEventHandlingComponent(new ClassLevelPolicyEventHandler());
            applyFallbackPolicy(component);
            var event = EventTestUtils.asEventMessage("test-event");

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, messageProcessingContext(event));

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @Test
        void should_prefer_method_policy_over_fallback_policy() {
            // given
            var component = annotatedEventHandlingComponent(new MethodLevelPolicyEventHandler());
            applyFallbackPolicy(component);
            var event = EventTestUtils.asEventMessage("test-event");

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, messageProcessingContext(event));

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void should_use_fallback_policy_for_handlers_without_policy_next_to_handlers_with_one() {
            // given
            var component = annotatedEventHandlingComponent(new MixedHandlersEventHandler());
            applyFallbackPolicy(component);
            var stringEvent = EventTestUtils.asEventMessage("test-event");
            var orderEvent = EventTestUtils.asEventMessage(new OrderEvent("order-1", "item-1"));

            // when
            var stringSequenceId = component.sequenceIdentifierFor(stringEvent, messageProcessingContext(stringEvent));
            var orderSequenceId = component.sequenceIdentifierFor(orderEvent, messageProcessingContext(orderEvent));

            // then
            assertThat(stringSequenceId).isEqualTo(FULL_SEQUENTIAL_POLICY);
            assertThat(orderSequenceId).isEqualTo("processor-wide");
        }

        @Test
        void should_not_fall_back_to_default_policy_when_processor_policy_resolves_no_sequence_identifier() {
            // given
            var component = annotatedEventHandlingComponent(new EventHandlerWithoutPolicy());
            component.unwrap(SimpleEventHandlingComponent.class)
                     .orElseThrow()
                     .overridePolicyWhenDefault((event, context) -> Optional.empty());
            var event = EventTestUtils.asEventMessage("test-event");

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, messageProcessingContext(event));

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        private void applyFallbackPolicy(AnnotatedEventHandlingComponent<?> component) {
            component.unwrap(SimpleEventHandlingComponent.class)
                     .orElseThrow()
                     .overridePolicyWhenDefault(fallbackPolicy);
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithoutPolicy {

            @EventHandler
            void handle(String event) {
            }
        }

        @SuppressWarnings({"DefaultAnnotationParam", "unused"})
        @SequencingPolicy(type = SequentialPolicy.class)
        private static class ClassLevelPolicyEventHandler {

            @EventHandler
            void handle(String event) {
            }
        }

        @SuppressWarnings("unused")
        private static class MethodLevelPolicyEventHandler {

            @EventHandler
            @SequencingPolicy(type = FullConcurrencyPolicy.class)
            void handle(String event) {
            }
        }

        @SuppressWarnings({"DefaultAnnotationParam", "unused"})
        private static class MixedHandlersEventHandler {

            @EventHandler
            @SequencingPolicy(type = SequentialPolicy.class)
            void handleString(String event) {
            }

            @EventHandler
            void handleOrder(OrderEvent event) {
            }
        }
    }

    private static ProcessingContext messageProcessingContext(EventMessage event) {
        return StubProcessingContext
                .withComponent(Converter.class, PassThroughConverter.INSTANCE)
                .withMessage(event)
                .withResource(LegacyResources.AGGREGATE_TYPE_KEY, AGGREGATE_TYPE)
                .withResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, AGGREGATE_IDENTIFIER)
                .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, 0L);
    }

    private static AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent(Object eventHandler) {
        return new AnnotatedEventHandlingComponent<>(
                eventHandler,
                ClasspathParameterResolverFactory.forClass(eventHandler.getClass()),
                ClasspathHandlerDefinition.forClass(eventHandler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );
    }

    private record OrderEvent(String orderId, String itemId) {

    }

    private record CustomerEvent(String customerId, String name) {

    }
}
