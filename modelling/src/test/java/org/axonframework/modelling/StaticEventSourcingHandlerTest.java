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

package org.axonframework.modelling;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.modelling.annotation.StaticEventSourcingHandlerParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector.inspectType;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating {@code static} {@code @EventHandler} support in the
 * {@link AnnotationBasedEntityEvolvingComponent}, where the current entity state is injected as the first argument and
 * may be {@code null}. This expresses a functional evolve step of the form {@code (@Nullable State, Event) -> State},
 * covering create-from-{@code null}, ordinary evolution, declining creation, and removal (tombstone).
 *
 * @author Mateusz Nowak
 */
class StaticEventSourcingHandlerTest {

    private static final EventConverter converter = new DelegatingEventConverter(new JacksonConverter());
    private static final ClassBasedMessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

    private static final EntityEvolver<Counter> COUNTER_EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
            Counter.class, converter, messageTypeResolver
    );

    private record Created() {

    }

    private record Incremented(int by) {

    }

    private record Deleted() {

    }

    @SuppressWarnings("unused")
    private static final class Counter {

        private final int value;

        private Counter(int value) {
            this.value = value;
        }

        @EventHandler
        static Counter onCreated(@Nullable Counter state, Created event) {
            return new Counter(0);
        }

        @EventHandler
        static Counter onIncremented(@Nullable Counter state, Incremented event) {
            // A functional evolve: only meaningful once the counter exists.
            return state == null ? null : new Counter(state.value + event.by());
        }

        @EventHandler
        static Counter onDeleted(@Nullable Counter state, Deleted event) {
            // Removal: the entity no longer exists after this event.
            return null;
        }
    }

    @Nested
    class CreationFromNull {

        @Test
        void staticHandlerCreatesEntityFromNullOnFirstEvent() {
            // given
            EventMessage event = asEventMessage(new Created());
            var context = StubProcessingContext.forMessage(event);

            // when
            Counter result = COUNTER_EVOLVER.evolve(null, event, context);

            // then
            assertThat(result).isNotNull();
            assertThat(result.value).isZero();
        }

        @Test
        void staticHandlerReturningNullDeclinesCreation() {
            // given / when
            EventMessage event = asEventMessage(new Incremented(5));
            var context = StubProcessingContext.forMessage(event);

            // when - the counter does not exist yet and the handler declines to create it
            Counter result = COUNTER_EVOLVER.evolve(null, event, context);

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    class Evolution {

        @Test
        void staticHandlerEvolvesExistingStateOnSubsequentEvents() {
            // given
            Counter created = COUNTER_EVOLVER.evolve(null,
                                                     asEventMessage(new Created()),
                                                     StubProcessingContext.forMessage(asEventMessage(new Created())));

            // when
            EventMessage increment = asEventMessage(new Incremented(3));
            Counter result = COUNTER_EVOLVER.evolve(created, increment, StubProcessingContext.forMessage(increment));

            // then
            assertThat(result).isNotNull();
            assertThat(result.value).isEqualTo(3);
        }

        @Test
        void foldsSequenceOfEventsFromNull() {
            // given
            EventMessage created = asEventMessage(new Created());
            EventMessage inc2 = asEventMessage(new Incremented(2));
            EventMessage inc5 = asEventMessage(new Incremented(5));

            // when
            Counter state = COUNTER_EVOLVER.evolve(null, created, StubProcessingContext.forMessage(created));
            state = COUNTER_EVOLVER.evolve(state, inc2, StubProcessingContext.forMessage(inc2));
            state = COUNTER_EVOLVER.evolve(state, inc5, StubProcessingContext.forMessage(inc5));

            // then
            assertThat(state).isNotNull();
            assertThat(state.value).isEqualTo(7);
        }
    }

    @Nested
    class NoStateParameter {

        @SuppressWarnings("unused")
        private static final class FromEvent {

            private final int value;

            private FromEvent(int value) {
                this.value = value;
            }

            // Static handler with NO state parameter: creates from the event, ignoring any prior state.
            @EventHandler
            static FromEvent onCreated(Created event) {
                return new FromEvent(0);
            }
        }

        private final EntityEvolver<FromEvent> evolver = new AnnotationBasedEntityEvolvingComponent<>(
                FromEvent.class, converter, messageTypeResolver
        );

        @Test
        void staticHandlerWithoutStateParameterCreatesFromEvent() {
            // given
            var event = asEventMessage(new Created());
            var context = StubProcessingContext.forMessage(event);

            // when the entity does not exist yet
            FromEvent result = evolver.evolve(null, event, context);

            // then the static handler still creates it from the event, without declaring a state parameter
            assertThat(result).isNotNull();
            assertThat(result.value).isZero();
        }
    }

    @Nested
    class Removal {

        @Test
        void staticHandlerReturningNullForExistingEntityIsRejected() {
            // given a created counter
            EventMessage created = asEventMessage(new Created());
            Counter state = COUNTER_EVOLVER.evolve(null, created, StubProcessingContext.forMessage(created));

            // when a static handler returns null for the now-existing entity, then it is rejected: an entity that
            // exists cannot be removed by returning null (model end-of-life as a terminal state instead).
            EventMessage deleted = asEventMessage(new Deleted());
            var context = StubProcessingContext.forMessage(deleted);
            assertThatThrownBy(() -> COUNTER_EVOLVER.evolve(state, deleted, context))
                    .isInstanceOf(StateEvolvingException.class)
                    .hasMessageContaining("cannot be removed by returning null");
        }
    }

    @Nested
    class InstanceHandlerInteraction {

        @SuppressWarnings("unused")
        private static final class MixedCounter {

            private int value;
            private boolean instanceHandlerInvoked;

            @EventHandler
            static MixedCounter onCreated(@Nullable MixedCounter state, Created event) {
                return new MixedCounter();
            }

            @EventHandler
            void onIncremented(Incremented event) {
                // Instance handler: can only run once the entity exists.
                this.value += event.by();
                this.instanceHandlerInvoked = true;
            }
        }

        private static final EntityEvolver<MixedCounter> MIXED_EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
                MixedCounter.class, converter, messageTypeResolver
        );

        @Test
        void instanceHandlerIsSkippedWhileStateIsNull() {
            // given / when - no static handler for Incremented, entity absent
            EventMessage increment = asEventMessage(new Incremented(4));
            MixedCounter result = MIXED_EVOLVER.evolve(null, increment, StubProcessingContext.forMessage(increment));

            // then
            assertThat(result).isNull();
        }

        @Test
        void instanceHandlerRunsAfterStaticCreation() {
            // given
            EventMessage created = asEventMessage(new Created());
            MixedCounter state = MIXED_EVOLVER.evolve(null, created, StubProcessingContext.forMessage(created));

            // when
            EventMessage increment = asEventMessage(new Incremented(4));
            state = MIXED_EVOLVER.evolve(state, increment, StubProcessingContext.forMessage(increment));

            // then
            assertThat(state).isNotNull();
            assertThat(state.value).isEqualTo(4);
            assertThat(state.instanceHandlerInvoked).isTrue();
        }
    }

    @Nested
    class ParameterResolution {

        @SuppressWarnings("unused")
        private record MetadataAware(String lastMetadata) {

            @EventHandler
            static MetadataAware onCreated(@Nullable MetadataAware state,
                                           Created event,
                                           @MetadataValue("sampleKey") String metadata) {
                return new MetadataAware(metadata);
            }
        }

        @Test
        void resolvesEventPayloadAndAdditionalParametersAlongsideNullableState() {
            // given
            EntityEvolver<MetadataAware> evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    MetadataAware.class, converter, messageTypeResolver
            );
            EventMessage event = new org.axonframework.messaging.eventhandling.GenericEventMessage(
                    messageTypeResolver.resolveOrThrow(Created.class),
                    new Created(),
                    org.axonframework.messaging.core.Metadata.with("sampleKey", "sampleValue")
            );
            var context = StubProcessingContext.forMessage(event);

            // when
            MetadataAware result = evolver.evolve(null, event, context);

            // then
            assertThat(result).isNotNull();
            assertThat(result.lastMetadata()).isEqualTo("sampleValue");
        }
    }

    @Nested
    class PolymorphicSuperTypeCreation {

        sealed interface Shape permits Circle, Square {

            @EventHandler
            static Shape onCreated(@Nullable Shape state, CreatedCircle event) {
                return new Circle(event.radius());
            }
        }

        record Circle(int radius) implements Shape {

        }

        record Square(int side) implements Shape {

        }

        private record CreatedCircle(int radius) {

        }

        @Test
        void staticSuperTypeHandlerCreatesConcreteSubtypeFromNull() {
            // given
            EntityEvolver<Shape> evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    Shape.class,
                    inspectType(
                            Shape.class,
                            messageTypeResolver,
                            MultiParameterResolverFactory.ordered(
                                    new StaticEventSourcingHandlerParameterResolverFactory(),
                                    ClasspathParameterResolverFactory.forClass(Shape.class)
                            ),
                            ClasspathHandlerDefinition.forClass(Shape.class),
                            Set.of(Circle.class, Square.class)
                    ),
                    converter,
                    messageTypeResolver
            );
            EventMessage event = asEventMessage(new CreatedCircle(7));
            var context = StubProcessingContext.forMessage(event);

            // when
            Shape result = evolver.evolve(null, event, context);

            // then
            assertThat(result).isInstanceOf(Circle.class);
            assertThat(((Circle) result).radius()).isEqualTo(7);
        }
    }
}
