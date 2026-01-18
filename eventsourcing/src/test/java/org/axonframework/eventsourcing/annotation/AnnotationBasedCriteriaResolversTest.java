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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AnnotationBasedCriteriaResolvers}.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 */
class AnnotationBasedCriteriaResolversTest {

    private static final Configuration configuration = mock(Configuration.class);

    @BeforeEach
    void setUp() {
        reset(configuration);
    }

    @Nested
    class SeparateCriteriaBuilders {

        @Test
        void usesSourceCriteriaBuilderForSourcing() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithSeparateCriteriaBuilders.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("account-123", new StubProcessingContext());

            // then
            assertThat(sourceCriteria).isEqualTo(
                    EventCriteria.havingTags("accountId", "account-123")
                                 .andBeingOneOfTypes("CreditsIncreased", "CreditsDecreased")
            );
        }

        @Test
        void usesAppendCriteriaBuilderForAppending() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithSeparateCriteriaBuilders.class,
                    String.class,
                    configuration
            );

            // when
            var appendCriteria = resolvers.appendCriteriaResolver().resolve("account-123", new StubProcessingContext());

            // then
            assertThat(appendCriteria).isEqualTo(
                    EventCriteria.havingTags("accountId", "account-123")
                                 .andBeingOneOfTypes("CreditsDecreased")
            );
        }

        @EventSourcedEntity(tagKey = "accountId")
        static class EntityWithSeparateCriteriaBuilders {

            @SourceCriteriaBuilder
            public static EventCriteria sourceCriteria(String accountId) {
                return EventCriteria
                        .havingTags("accountId", accountId)
                        .andBeingOneOfTypes("CreditsIncreased", "CreditsDecreased");
            }

            @AppendCriteriaBuilder
            public static EventCriteria appendCriteria(String accountId) {
                return EventCriteria
                        .havingTags("accountId", accountId)
                        .andBeingOneOfTypes("CreditsDecreased");
            }
        }
    }

    @Nested
    class EventCriteriaBuilderFallback {

        @Test
        void usesEventCriteriaBuilderForBothWhenNoSeparateBuildersPresent() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithOnlyEventCriteriaBuilder.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("order-456", new StubProcessingContext());
            var appendCriteria = resolvers.appendCriteriaResolver().resolve("order-456", new StubProcessingContext());

            // then
            var expectedCriteria = EventCriteria.havingTags("orderId", "order-456");
            assertThat(sourceCriteria).isEqualTo(expectedCriteria);
            assertThat(appendCriteria).isEqualTo(expectedCriteria);
        }

        @EventSourcedEntity(tagKey = "orderId")
        static class EntityWithOnlyEventCriteriaBuilder {

            @EventCriteriaBuilder
            public static EventCriteria buildCriteria(String orderId) {
                return EventCriteria.havingTags("orderId", orderId);
            }
        }
    }

    @Nested
    class MixedAnnotations {

        @Test
        void usesSourceCriteriaBuilderAndFallsBackToEventCriteriaBuilderForAppend() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithSourceAndEventCriteriaBuilder.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("id-123", new StubProcessingContext());
            var appendCriteria = resolvers.appendCriteriaResolver().resolve("id-123", new StubProcessingContext());

            // then
            assertThat(sourceCriteria).isEqualTo(
                    EventCriteria.havingTags("sourceOnly", "id-123")
            );
            assertThat(appendCriteria).isEqualTo(
                    EventCriteria.havingTags("generic", "id-123")
            );
        }

        @EventSourcedEntity(tagKey = "fallbackTag")
        static class EntityWithSourceAndEventCriteriaBuilder {

            @SourceCriteriaBuilder
            public static EventCriteria sourceCriteria(String id) {
                return EventCriteria.havingTags("sourceOnly", id);
            }

            @EventCriteriaBuilder
            public static EventCriteria genericCriteria(String id) {
                return EventCriteria.havingTags("generic", id);
            }
        }

        @Test
        void usesAppendCriteriaBuilderAndFallsBackToEventCriteriaBuilderForSource() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithAppendAndEventCriteriaBuilder.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("id-456", new StubProcessingContext());
            var appendCriteria = resolvers.appendCriteriaResolver().resolve("id-456", new StubProcessingContext());

            // then
            assertThat(sourceCriteria).isEqualTo(
                    EventCriteria.havingTags("generic", "id-456")
            );
            assertThat(appendCriteria).isEqualTo(
                    EventCriteria.havingTags("appendOnly", "id-456")
            );
        }

        @EventSourcedEntity(tagKey = "fallbackTag")
        static class EntityWithAppendAndEventCriteriaBuilder {

            @AppendCriteriaBuilder
            public static EventCriteria appendCriteria(String id) {
                return EventCriteria.havingTags("appendOnly", id);
            }

            @EventCriteriaBuilder
            public static EventCriteria genericCriteria(String id) {
                return EventCriteria.havingTags("generic", id);
            }
        }
    }

    @Nested
    class TagBasedFallback {

        @Test
        void usesTagKeyFallbackWhenNoAnnotationsPresent() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithNoBuilders.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("my-id", new StubProcessingContext());
            var appendCriteria = resolvers.appendCriteriaResolver().resolve("my-id", new StubProcessingContext());

            // then
            var expectedCriteria = EventCriteria.havingTags("customTagKey", "my-id");
            assertThat(sourceCriteria).isEqualTo(expectedCriteria);
            assertThat(appendCriteria).isEqualTo(expectedCriteria);
        }

        @EventSourcedEntity(tagKey = "customTagKey")
        static class EntityWithNoBuilders {
        }

        @Test
        void usesSimpleClassNameAsTagNameWhenNoTagKeySet() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithEmptyTagKey.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("my-id", new StubProcessingContext());

            // then
            assertThat(sourceCriteria).isEqualTo(
                    EventCriteria.havingTags("EntityWithEmptyTagKey", "my-id")
            );
        }

        @EventSourcedEntity
        static class EntityWithEmptyTagKey {
        }

        @Test
        void usesAppendCriteriaBuilderOnlyAndFallsBackToTagForSource() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithAppendCriteriaBuilderOnly.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("id-789", new StubProcessingContext());
            var appendCriteria = resolvers.appendCriteriaResolver().resolve("id-789", new StubProcessingContext());

            // then
            assertThat(sourceCriteria).isEqualTo(
                    EventCriteria.havingTags("tagKey", "id-789")
            );
            assertThat(appendCriteria).isEqualTo(
                    EventCriteria.havingTags("appendOnly", "id-789")
            );
        }

        @EventSourcedEntity(tagKey = "tagKey")
        static class EntityWithAppendCriteriaBuilderOnly {

            @AppendCriteriaBuilder
            public static EventCriteria appendCriteria(String id) {
                return EventCriteria.havingTags("appendOnly", id);
            }
        }
    }

    @Nested
    class MultipleIdTypes {

        @Test
        void resolvesCorrectBuilderBasedOnIdType() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithMultipleIdTypes.class,
                    Object.class,
                    configuration
            );

            // when
            var sourceCriteriaString = resolvers.sourceCriteriaResolver().resolve("string-id", new StubProcessingContext());
            var appendCriteriaString = resolvers.appendCriteriaResolver().resolve("string-id", new StubProcessingContext());

            var sourceCriteriaLong = resolvers.sourceCriteriaResolver().resolve(42L, new StubProcessingContext());
            var appendCriteriaLong = resolvers.appendCriteriaResolver().resolve(42L, new StubProcessingContext());

            // then
            assertThat(sourceCriteriaString).isEqualTo(
                    EventCriteria.havingTags("stringSourceId", "string-id")
            );
            assertThat(appendCriteriaString).isEqualTo(
                    EventCriteria.havingTags("stringAppendId", "string-id")
            );
            assertThat(sourceCriteriaLong).isEqualTo(
                    EventCriteria.havingTags("longSourceId", "42")
            );
            assertThat(appendCriteriaLong).isEqualTo(
                    EventCriteria.havingTags("longAppendId", "42")
            );
        }

        @EventSourcedEntity(tagKey = "fallbackTag")
        static class EntityWithMultipleIdTypes {

            @SourceCriteriaBuilder
            public static EventCriteria sourceStringCriteria(String id) {
                return EventCriteria.havingTags("stringSourceId", id);
            }

            @SourceCriteriaBuilder
            public static EventCriteria sourceLongCriteria(Long id) {
                return EventCriteria.havingTags("longSourceId", id.toString());
            }

            @AppendCriteriaBuilder
            public static EventCriteria appendStringCriteria(String id) {
                return EventCriteria.havingTags("stringAppendId", id);
            }

            @AppendCriteriaBuilder
            public static EventCriteria appendLongCriteria(Long id) {
                return EventCriteria.havingTags("longAppendId", id.toString());
            }
        }
    }

    @Nested
    class ConfigurationProblems {

        @Test
        void throwsWhenNotAnnotatedWithEventSourcedEntity() {
            // when / then
            assertThatThrownBy(() -> new AnnotationBasedCriteriaResolvers<>(
                    NonEventSourcedEntity.class,
                    String.class,
                    configuration
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The given class is not an @EventSourcedEntity");
        }

        static class NonEventSourcedEntity {
        }

        @Test
        void throwsWhenSourceCriteriaBuilderReturnsNull() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithNullReturningSourceBuilder.class,
                    String.class,
                    configuration
            );

            // when / then
            assertThatThrownBy(() -> resolvers.sourceCriteriaResolver().resolve("id", new StubProcessingContext()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("returned null");
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithNullReturningSourceBuilder {

            @SourceCriteriaBuilder
            public static EventCriteria sourceCriteria(String id) {
                return null;
            }
        }

        @Test
        void throwsWhenAppendCriteriaBuilderReturnsNull() {
            // given
            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithNullReturningAppendBuilder.class,
                    String.class,
                    configuration
            );

            // when / then
            assertThatThrownBy(() -> resolvers.appendCriteriaResolver().resolve("id", new StubProcessingContext()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("returned null");
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithNullReturningAppendBuilder {

            @AppendCriteriaBuilder
            public static EventCriteria appendCriteria(String id) {
                return null;
            }
        }

        @Test
        void throwsWhenSourceCriteriaBuilderIsNotStatic() {
            // when / then
            assertThatThrownBy(() -> new AnnotationBasedCriteriaResolvers<>(
                    EntityWithNonStaticSourceBuilder.class,
                    String.class,
                    configuration
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be static");
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithNonStaticSourceBuilder {

            @SourceCriteriaBuilder
            public EventCriteria sourceCriteria(String id) {
                return EventCriteria.havingTags("id", id);
            }
        }

        @Test
        void throwsWhenAppendCriteriaBuilderIsNotStatic() {
            // when / then
            assertThatThrownBy(() -> new AnnotationBasedCriteriaResolvers<>(
                    EntityWithNonStaticAppendBuilder.class,
                    String.class,
                    configuration
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be static");
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithNonStaticAppendBuilder {

            @AppendCriteriaBuilder
            public EventCriteria appendCriteria(String id) {
                return EventCriteria.havingTags("id", id);
            }
        }

        @Test
        void throwsWhenDuplicateSourceCriteriaBuildersForSameIdType() {
            // when / then
            assertThatThrownBy(() -> new AnnotationBasedCriteriaResolvers<>(
                    EntityWithDuplicateSourceBuilders.class,
                    String.class,
                    configuration
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Multiple @SourceCriteriaBuilder methods found");
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithDuplicateSourceBuilders {

            @SourceCriteriaBuilder
            public static EventCriteria sourceOne(String id) {
                return EventCriteria.havingTags("id", id);
            }

            @SourceCriteriaBuilder
            public static EventCriteria sourceTwo(String id) {
                return EventCriteria.havingTags("id", id);
            }
        }

        @Test
        void throwsWhenDuplicateAppendCriteriaBuildersForSameIdType() {
            // when / then
            assertThatThrownBy(() -> new AnnotationBasedCriteriaResolvers<>(
                    EntityWithDuplicateAppendBuilders.class,
                    String.class,
                    configuration
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Multiple @AppendCriteriaBuilder methods found");
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithDuplicateAppendBuilders {

            @AppendCriteriaBuilder
            public static EventCriteria appendOne(String id) {
                return EventCriteria.havingTags("id", id);
            }

            @AppendCriteriaBuilder
            public static EventCriteria appendTwo(String id) {
                return EventCriteria.havingTags("id", id);
            }
        }
    }

    @Nested
    class ComponentInjection {

        @Test
        void canInjectConfigurationIntoSourceCriteriaBuilder() {
            // given
            when(configuration.getOptionalComponent(Configuration.class))
                    .thenReturn(Optional.of(configuration));

            var resolvers = new AnnotationBasedCriteriaResolvers<>(
                    EntityWithConfigurationInjection.class,
                    String.class,
                    configuration
            );

            // when
            var sourceCriteria = resolvers.sourceCriteriaResolver().resolve("id", new StubProcessingContext());

            // then
            assertThat(sourceCriteria).isEqualTo(EventCriteria.havingTags("withConfig", "id"));
        }

        @EventSourcedEntity(tagKey = "tag")
        static class EntityWithConfigurationInjection {

            @SourceCriteriaBuilder
            public static EventCriteria sourceCriteria(String id, Configuration config) {
                assertThat(config).isNotNull();
                return EventCriteria.havingTags("withConfig", id);
            }
        }
    }
}
