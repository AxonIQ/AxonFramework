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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.test.appender.ListAppender;
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
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

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
        void shouldUseDefaultHierarchicalSequencingWhenNoPolicySpecified() {
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

    @Nested
    class ComponentLevelSequencingPolicy {

        @Test
        void shouldUseSequentialPolicyWhenSetOnComponent() {
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
        void shouldUseFullConcurrencyPolicyWhenSetOnComponent() {
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
        void shouldUseMetadataSequencingPolicyWhenSetOnComponent() {
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
        void shouldUseMetadataSequencingPolicyFallbackToEventIdentifier() {
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
        void shouldUsePropertySequencingPolicyWhenSetOnComponent() {
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
        void shouldUseSequentialPerAggregatePolicyWhenSetOnComponent() {
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
        void shouldUseNestedComponentOverMainComponentPolicy() {
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
        void shouldUseMainComponentPolicyWhenNoNestedComponent() {
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
        void shouldUseMainComponentPolicyWhenMixedHandlers() {
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

    @Nested
    class PolicyCannotDetermineAnIdentifier {

        @Test
        void shouldFallBackToEventIdentifierForNoOpPolicy() {
            // given a policy that imposes no sequencing at all, so it never resolves an identifier
            var component = SimpleEventHandlingComponent.create("test", NoOpSequencingPolicy.INSTANCE);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then every event gets its own identifier, imposing no sequencing between events
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void shouldFallBackToEventIdentifierWhenNoAggregateIdentifierIsPresent() {
            // given a per-aggregate policy on a store that populates no aggregate identifier resource
            var component = SimpleEventHandlingComponent.create("test", SequentialPerAggregatePolicy.INSTANCE);
            var event = EventTestUtils.asEventMessage("test-event");
            var context = contextWithoutAggregateIdentifier(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void shouldFallBackToEventIdentifierWhenOnlyPlainHandlersAreSubscribed() {
            // given the shape of an annotated projection: plain handlers, no nested component, no sequencing
            var component = SimpleEventHandlingComponent.create("test", NoOpSequencingPolicy.INSTANCE);
            component.subscribe(new QualifiedName("java.lang", "String"), new PlainEventHandler());
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = component.sequenceIdentifierFor(event, context);

            // then
            assertThat(sequenceIdentifier).isEqualTo(event.identifier());
        }

        @Test
        void shouldNotConsultTheComponentPolicyWhenANestedComponentResolvedAnIdentifier() {
            // given a main component whose own policy never resolves an identifier
            var mainComponent = SimpleEventHandlingComponent.create("main", NoOpSequencingPolicy.INSTANCE);
            mainComponent.subscribe(
                    new QualifiedName("java.lang", "String"),
                    SimpleEventHandlingComponent.create("nested", SequentialPolicy.INSTANCE)
                                                .subscribe(new QualifiedName("java.lang", "String"),
                                                           new PlainEventHandler())
            );
            var event = EventTestUtils.asEventMessage("test-event");
            var context = messageProcessingContext(event);

            // when
            var sequenceIdentifier = mainComponent.sequenceIdentifierFor(event, context);

            // then the nested component's answer is used, and the component policy is never resolved
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }
    }

    @Nested
    class FallbackToEventIdentifierIsLogged {

        private ListAppender appender;
        private Logger componentLogger;
        private boolean previousAdditive;

        @BeforeEach
        void attachAppender() {
            appender = new ListAppender("SequencingFallbackTestAppender");
            appender.start();

            componentLogger = (Logger) LogManager.getLogger(SimpleEventHandlingComponent.class);
            previousAdditive = componentLogger.isAdditive();
            componentLogger.setAdditive(false);
            componentLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            componentLogger.removeAppender(appender);
            componentLogger.setAdditive(previousAdditive);
            appender.stop();
        }

        @Test
        void shouldWarnOnceNamingThePolicyHoweverManyEventsFallBack() {
            // given a per-aggregate policy on a store that populates no aggregate identifier resource, so the
            // per-aggregate ordering that was asked for is silently not delivered
            var component = SimpleEventHandlingComponent.create("projection", SequentialPerAggregatePolicy.INSTANCE);
            component.subscribe(new QualifiedName("java.lang", "String"), new PlainEventHandler());

            // when five events in a row fall back
            for (int i = 0; i < 5; i++) {
                var event = EventTestUtils.asEventMessage("test-event");
                component.sequenceIdentifierFor(event, contextWithoutAggregateIdentifier(event));
            }

            // then a single warning names both the policy and the component, rather than one per event
            assertThat(appender.getEvents()).hasSize(1);
            LogEvent warning = appender.getEvents().getFirst();
            assertThat(warning.getLevel()).isEqualTo(Level.WARN);
            assertThat(warning.getMessage().getFormattedMessage())
                    .contains(SequentialPerAggregatePolicy.class.getName())
                    .contains("projection");
        }

        @Test
        void shouldNotWarnForTheNoOpSequencingPolicy() {
            // given a policy whose empty result is the configured intent rather than a downgrade
            var component = SimpleEventHandlingComponent.create("projection", NoOpSequencingPolicy.INSTANCE);
            component.subscribe(new QualifiedName("java.lang", "String"), new PlainEventHandler());

            // when five events in a row fall back to the event identifier
            for (int i = 0; i < 5; i++) {
                var event = EventTestUtils.asEventMessage("test-event");
                component.sequenceIdentifierFor(event, messageProcessingContext(event));
            }

            // then nothing is logged, as asking for no sequencing and getting none is not a degradation
            assertThat(appender.getEvents()).isEmpty();
        }

        @Test
        void shouldNotWarnForTheDefaultSequencingPolicy() {
            // given the default hierarchical policy, which always resolves an identifier
            var component = SimpleEventHandlingComponent.create("test");
            var aggregateEvent = EventTestUtils.asEventMessage("test-event");
            var plainEvent = EventTestUtils.asEventMessage("test-event");

            // when asked both with and without an aggregate identifier in the context
            component.sequenceIdentifierFor(aggregateEvent, messageProcessingContext(aggregateEvent));
            component.sequenceIdentifierFor(plainEvent, contextWithoutAggregateIdentifier(plainEvent));

            // then nothing is logged, as the secondary SequentialPolicy answers when the primary policy cannot
            assertThat(appender.getEvents()).isEmpty();
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

    private static ProcessingContext messageProcessingContext(EventMessage event) {
        return StubProcessingContext
                .withComponent(Converter.class, PassThroughConverter.INSTANCE)
                .withMessage(event)
                .withResource(LegacyResources.AGGREGATE_TYPE_KEY, AGGREGATE_TYPE)
                .withResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, AGGREGATE_IDENTIFIER)
                .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, 0L);
    }

    private static ProcessingContext contextWithoutAggregateIdentifier(EventMessage event) {
        return StubProcessingContext
                .withComponent(Converter.class, PassThroughConverter.INSTANCE)
                .withMessage(event);
    }
}