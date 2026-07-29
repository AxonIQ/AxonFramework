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

import org.jspecify.annotations.NonNull;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.FullConcurrencyPolicy;
import org.axonframework.messaging.core.sequencing.HierarchicalSequencingPolicy;
import org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentMatcher;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.sequencing.SequentialPolicy.FULL_SEQUENTIAL_POLICY;

/**
 * Test class validating the {@link SimpleEventHandlingComponent} sequencing policy behavior. Verifies that
 * {@code sequenceIdentifierFor} returns correct values based on component-level and nested component-level sequencing
 * policies.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class SimpleEventHandlingComponentSequencingPolicyTest {

    public static final String AGGREGATE_TYPE = "test";
    public static final String AGGREGATE_IDENTIFIER = "id";

    @Nested
    class DefaultSequencingPolicy {

        @Test
        void should_use_default_hierarchical_sequencing_when_no_policy_specified() {
            // given
            var component = SimpleEventHandlingComponent.create("test");
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(AGGREGATE_IDENTIFIER);
        }
    }

    /**
     * The default policy on an event store that publishes no aggregate identifier, which is every store speaking the
     * Dynamic Consistency Boundary protocol. Pins what the Javadoc on
     * {@link SimpleEventHandlingComponent#create(String)} promises there: the per-aggregate half resolves nothing, so
     * the fallback answers with one constant identifier for the whole stream, and that one identifier hashes into
     * exactly one segment.
     */
    @Nested
    class DefaultSequencingPolicyWithoutAnAggregateIdentifier {

        @Test
        void collapsesEventsOfIndependentEntitiesOntoOneSequenceIdentifier() {
            // given a component with the wired default, and events carrying no aggregate identifier resource
            var component = SimpleEventHandlingComponent.create("test");
            var firstEvent = EventTestUtils.asEventMessage(new OrderEvent("order-1", "item-1"));
            var secondEvent = EventTestUtils.asEventMessage(new OrderEvent("order-2", "item-2"));

            // when
            var firstIdentifier = component.sequenceIdentifierFor(firstEvent,
                                                                 contextWithoutAggregateIdentifier(firstEvent));
            var secondIdentifier = component.sequenceIdentifierFor(secondEvent,
                                                                   contextWithoutAggregateIdentifier(secondEvent));

            // then both collapse onto the fallback's constant, rather than onto their own entity
            assertThat(firstIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
            assertThat(secondIdentifier).isEqualTo(firstIdentifier);
        }

        @Test
        void routesEveryEventIntoOneOfFourSegmentsLeavingTheOtherThreeEmpty() {
            // given four segments, and a component with the wired default
            var component = SimpleEventHandlingComponent.create("test");
            List<Segment> segments = Segment.splitBalanced(Segment.ROOT_SEGMENT, 3);
            assertThat(segments).hasSize(4);

            // when routing events of six independent entities through the same segment matching the processor applies
            var matcher = new SegmentMatcher((e, ctx) -> Optional.of(component.sequenceIdentifierFor(e, ctx)));
            Map<Integer, Long> eventsPerSegment = new LinkedHashMap<>();
            for (int entity = 0; entity < 6; entity++) {
                var event = EventTestUtils.asEventMessage(new OrderEvent("order-" + entity, "item-" + entity));
                var context = contextWithoutAggregateIdentifier(event);
                for (Segment segment : segments) {
                    if (matcher.matches(segment, event, context)) {
                        eventsPerSegment.merge(segment.getSegmentId(), 1L, Long::sum);
                    }
                }
            }

            // then a single segment took all six, and the three other segments were never given anything to do
            assertThat(eventsPerSegment).hasSize(1);
            assertThat(eventsPerSegment.values()).containsExactly(6L);
        }
    }

    @Nested
    class ComponentLevelSequencingPolicy {

        @Test
        void should_use_sequential_policy_when_set_on_component() {
            // given
            var component = SimpleEventHandlingComponent.create("test", SequentialPolicy.INSTANCE);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @Test
        void should_use_full_concurrency_policy_when_set_on_component() {
            // given
            var component = SimpleEventHandlingComponent.create("test", FullConcurrencyPolicy.INSTANCE);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void should_use_metadata_sequencing_policy_when_set_on_component() {
            // given
            var component = SimpleEventHandlingComponent.create("test", new MetadataSequencingPolicy("userId"));
            var metadata = Metadata.with("userId", "user123");
            var event = new GenericEventMessage(new MessageType("test-event"), "test-event", metadata);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo("user123");
        }

        @Test
        void should_use_metadata_sequencing_policy_fallback_to_event_identifier() {
            // given
            var component = SimpleEventHandlingComponent.create(
                    "test",
                    new HierarchicalSequencingPolicy<>(
                            new MetadataSequencingPolicy("userId"),
                            (e, ctx) -> Optional.of(e.identifier())
                    )
            );
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void should_use_property_sequencing_policy_when_set_on_component() {
            // given
            var component = SimpleEventHandlingComponent.create(
                    "test",
                    new PropertySequencingPolicy<>(OrderEvent.class, "orderId")
            );
            var eventPayload = new OrderEvent("order123", "item456");
            var event = EventTestUtils.asEventMessage(eventPayload);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo("order123");
        }

        @Test
        void should_use_sequential_per_aggregate_policy_when_set_on_component() {
            // given
            var component = SimpleEventHandlingComponent.create("test", SequentialPerAggregatePolicy.INSTANCE);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(AGGREGATE_IDENTIFIER);
        }
    }

    @Nested
    class NestedComponentOverridesPolicy {

        @Test
        void should_use_nested_component_over_main_component_policy() {
            // given
            var mainComponent = SimpleEventHandlingComponent.create("test", FullConcurrencyPolicy.INSTANCE);

            mainComponent.subscribe(
                    new QualifiedName("java.lang", "String"),
                    SimpleEventHandlingComponent.create(
                            "nested", SequentialPolicy.INSTANCE
                    ).subscribe(new QualifiedName("java.lang", "String"), new PlainEventHandler())
            );

            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = mainComponent.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }
    }

    @Nested
    class MainComponentPolicyWhenNoNestedComponent {

        @Test
        void should_use_main_component_policy_when_no_nested_component() {
            // given
            var mainComponent = SimpleEventHandlingComponent.create("main", SequentialPolicy.INSTANCE);
            var plainEventHandler = new PlainEventHandler();

            mainComponent.subscribe(new QualifiedName("java.lang", "String"), plainEventHandler);

            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = mainComponent.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @Test
        void should_use_main_component_policy_when_mixed_handlers() {
            // given
            var mainComponent = SimpleEventHandlingComponent.create(
                    "main", new PropertySequencingPolicy<>(OrderEvent.class, "orderId")
            );
            var nestedComponent = SimpleEventHandlingComponent.create("nested", FullConcurrencyPolicy.INSTANCE);
            var nestedEventHandler = createEventHandlerFromComponent(nestedComponent);
            var plainEventHandler = new PlainEventHandler();

            mainComponent.subscribe(new QualifiedName(OrderEvent.class), nestedEventHandler);
            mainComponent.subscribe(new QualifiedName(OrderEvent.class), plainEventHandler);

            var eventPayload = new OrderEvent("order789", "item123");
            var event = EventTestUtils.asEventMessage(eventPayload);
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = mainComponent.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }
    }

    private static EventHandler createEventHandlerFromComponent(EventHandlingComponent component) {
        return component;
    }

    private static class PlainEventHandler implements EventHandler {

        @Override
        public MessageStream.@NonNull Empty<Message> handle(@NonNull EventMessage event, @NonNull ProcessingContext context) {
            return MessageStream.empty();
        }
    }

    private record OrderEvent(String orderId, String itemId) {

    }

    private static ProcessingContext contextWithoutAggregateIdentifier(EventMessage event) {
        return StubProcessingContext.withComponent(Converter.class, PassThroughConverter.INSTANCE)
                                    .withMessage(event);
    }

    private static ProcessingContext messageProcessingContext(EventMessage event) {
        return StubProcessingContext
                .withComponent(Converter.class, PassThroughConverter.INSTANCE)
                .withMessage(event)
                .withResource(LegacyResources.AGGREGATE_TYPE_KEY, AGGREGATE_TYPE)
                .withResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, AGGREGATE_IDENTIFIER)
                .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, 0L);
    }
}