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

package org.axonframework.eventsourcing.annotation.reflection;

import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests the behavior described on {@link ForcedEntityCreator}.
 * <p>
 * A no-arguments (or {@link InjectEntityId}-only) factory constructor or method annotated with
 * {@link ForcedEntityCreator} is invoked by {@link AnnotationBasedEventSourcedEntityFactory} even when no first event
 * is present. Since 5.4.0 a plain {@link EntityCreator} of the same shape does exactly the same, so this deprecated
 * annotation is redundant and behaves identically to {@link EntityCreator}. These tests pin that equivalence: the
 * forced form still always creates, and the plain form now creates in the same scenarios.
 *
 * @author Steven van Beelen
 */
@SuppressWarnings("removal")
class ForcedEntityCreatorTest {

    private final ParameterResolverFactory parameterResolverFactory =
            ClasspathParameterResolverFactory.forClass(getClass());
    private final MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    private final EventConverter converter = new DelegatingEventConverter(PassThroughConverter.INSTANCE);

    @Nested
    class NoArgumentCreators {

        @Test
        void forcedConstructorCreatesEntityWithoutFirstEvent() {
            // given
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    ForcedEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );

            // when
            ForcedEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            // then
            assertThat(entity).isNotNull();
        }

        @Test
        void plainConstructorAlsoCreatesEntityWithoutFirstEvent() {
            // given a plain @EntityCreator now behaves identically to the forced form
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    PlainEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );

            // when
            PlainEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            // then
            assertThat(entity).isNotNull();
        }

        public static class ForcedEntity {

            @ForcedEntityCreator
            public ForcedEntity() {
            }
        }

        public static class PlainEntity {

            @EntityCreator
            public PlainEntity() {
            }
        }
    }

    @Nested
    class IdentifierBasedCreators {

        @Test
        void forcedConstructorCreatesEntityWithoutFirstEvent() {
            // given
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    ForcedEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );

            // when
            ForcedEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            // then
            assertThat(entity).isNotNull();
            assertThat(entity.id).isEqualTo("entity-id");
        }

        @Test
        void plainConstructorAlsoCreatesEntityWithoutFirstEvent() {
            // given a plain @EntityCreator now behaves identically to the forced form
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    PlainEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );

            // when
            PlainEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            // then
            assertThat(entity).isNotNull();
            assertThat(entity.id).isEqualTo("entity-id");
        }

        public static class ForcedEntity {

            private final String id;

            @ForcedEntityCreator
            public ForcedEntity(@InjectEntityId String id) {
                this.id = id;
            }
        }

        public static class PlainEntity {

            private final String id;

            @EntityCreator
            public PlainEntity(@InjectEntityId String id) {
                this.id = id;
            }
        }
    }

    @Nested
    class StaticFactoryMethodCreators {

        @Test
        void forcedFactoryMethodCreatesEntityWithoutFirstEvent() {
            // given
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    ForcedEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );

            // when
            ForcedEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            // then
            assertThat(entity).isNotNull();
            assertThat(entity.id).isEqualTo("entity-id");
        }

        @Test
        void plainFactoryMethodAlsoCreatesEntityWithoutFirstEvent() {
            // given a plain @EntityCreator now behaves identically to the forced form
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    PlainEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );

            // when
            PlainEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            // then
            assertThat(entity).isNotNull();
            assertThat(entity.id).isEqualTo("entity-id");
        }

        public static class ForcedEntity {

            private final String id;

            private ForcedEntity(String id) {
                this.id = id;
            }

            @ForcedEntityCreator
            public static ForcedEntity create(@InjectEntityId String id) {
                return new ForcedEntity(id);
            }
        }

        public static class PlainEntity {

            private final String id;

            private PlainEntity(String id) {
                this.id = id;
            }

            @EntityCreator
            public static PlainEntity create(@InjectEntityId String id) {
                return new PlainEntity(id);
            }
        }
    }

    @Nested
    class EventBasedPrecedence {

        @Test
        void eventBasedCreatorTakesPrecedenceOverForcedIdBasedCreatorWhenFirstEventPresent() {
            // given
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    MixedEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );
            EventMessage firstEvent = new GenericEventMessage(
                    new MessageType(CreationPayload.class), new CreationPayload("payload-value")
            );

            // when
            MixedEntity entity =
                    factory.create("entity-id", firstEvent, StubProcessingContext.forMessage(firstEvent));

            // then
            assertThat(entity).isNotNull();
            assertThat(entity.source).isEqualTo("event");
            assertThat(entity.value).isEqualTo("payload-value");
        }

        @Test
        void forcedIdBasedCreatorStillAppliesWhenFirstEventPresentButNoEventBasedCreatorMatches() {
            // given
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    IdOnlyForcedEntity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );
            EventMessage firstEvent = new GenericEventMessage(new MessageType("unrelated-type"), "irrelevant");

            // when
            IdOnlyForcedEntity entity =
                    factory.create("entity-id", firstEvent, StubProcessingContext.forMessage(firstEvent));

            // then
            assertThat(entity).isNotNull();
            assertThat(entity.id).isEqualTo("entity-id");
        }

        public record CreationPayload(String value) {

        }

        public static class MixedEntity {

            private final String source;
            private final String value;

            @ForcedEntityCreator
            public MixedEntity(@InjectEntityId String id) {
                this.source = "id";
                this.value = null;
            }

            @EntityCreator
            public MixedEntity(CreationPayload payload) {
                this.source = "event";
                this.value = payload.value();
            }
        }

        public static class IdOnlyForcedEntity {

            private final String id;

            @ForcedEntityCreator
            public IdOnlyForcedEntity(@InjectEntityId String id) {
                this.id = id;
            }
        }
    }

    @Nested
    class PayloadQualifiedNamesOverride {

        @Test
        void forcedCreatorHonorsExplicitPayloadQualifiedNamesOverride() {
            // given
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    Entity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );
            EventMessage matchingEvent = new GenericEventMessage(new MessageType("custom-creation-type"), "payload");

            // when
            Entity entity =
                    factory.create("entity-id", matchingEvent, StubProcessingContext.forMessage(matchingEvent));

            // then
            assertThat(entity).isNotNull();
        }

        @Test
        void forcedCreatorDefersWhenPayloadQualifiedNameDoesNotMatch() {
            // given an event-based creator that matches no incoming event now defers creation by returning null
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    Entity.class, String.class, parameterResolverFactory, messageTypeResolver, converter
            );
            EventMessage nonMatchingEvent = new GenericEventMessage(new MessageType("other-type"), "payload");

            // when
            Entity entity =
                    factory.create("entity-id", nonMatchingEvent, StubProcessingContext.forMessage(nonMatchingEvent));

            // then
            assertThat(entity).isNull();
        }

        public static class Entity {

            @ForcedEntityCreator(payloadQualifiedNames = "custom-creation-type")
            public Entity(String payload) {
            }
        }
    }
}
