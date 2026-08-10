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
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class validating the independent sourcing and append precedence resolved by
 * {@link AnnotationBasedSourcingCriteriaResolver} and {@link AnnotationBasedAppendCriteriaResolver}, backed by the
 * shared {@link AnnotationBasedCriteriaBuilders} scan.
 *
 * @author Mateusz Nowak
 */
class AnnotationBasedCriteriaBuildersTest {

    private static final Configuration configuration = mock(Configuration.class);

    @Nested
    class PrecedenceMatrix {

        @EventSourcedEntity
        static class OnlySourcingBuilder {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcing(String id) {
                return EventCriteria.havingTags("sourcing", id);
            }
        }

        @Test
        void sourcingCriteriaBuilderIsUsedForSourcingWhenNoOtherBuilderExists() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(OnlySourcingBuilder.class,
                                                                                  Object.class, configuration);
            assertEquals(EventCriteria.havingTags("sourcing", "id"),
                         sourcingResolver.resolve("id", new StubProcessingContext()));
        }

        @Test
        void sourcingCriteriaBuilderIsNotUsedForAppendingFallsBackToTag() {
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(OnlySourcingBuilder.class,
                                                                              Object.class, configuration);
            assertEquals(EventCriteria.havingTags("OnlySourcingBuilder", "id"),
                         appendResolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity
        static class OnlyAppendBuilder {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria append(String id) {
                return EventCriteria.havingTags("append", id);
            }
        }

        @Test
        void appendCriteriaBuilderIsUsedForAppendingWhenNoOtherBuilderExists() {
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(OnlyAppendBuilder.class,
                                                                              Object.class, configuration);
            assertEquals(EventCriteria.havingTags("append", "id"),
                         appendResolver.resolve("id", new StubProcessingContext()));
        }

        @Test
        void appendCriteriaBuilderIsNotUsedForSourcingFallsBackToTag() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(OnlyAppendBuilder.class,
                                                                                  Object.class, configuration);
            assertEquals(EventCriteria.havingTags("OnlyAppendBuilder", "id"),
                         sourcingResolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity
        static class OnlySharedBuilder {

            @SuppressWarnings("unused")
            @EventCriteriaBuilder
            static EventCriteria shared(String id) {
                return EventCriteria.havingTags("shared", id);
            }
        }

        @Test
        void sharedEventCriteriaBuilderIsUsedForBothRolesWhenNoRoleSpecificBuilderExists() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(OnlySharedBuilder.class,
                                                                                  Object.class, configuration);
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(OnlySharedBuilder.class,
                                                                              Object.class, configuration);

            EventCriteria expected = EventCriteria.havingTags("shared", "id");
            assertEquals(expected, sourcingResolver.resolve("id", new StubProcessingContext()));
            assertEquals(expected, appendResolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity
        static class SourcingOverridesSharedForSourcingRoleOnly {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcing(String id) {
                return EventCriteria.havingTags("sourcing", id);
            }

            @SuppressWarnings("unused")
            @EventCriteriaBuilder
            static EventCriteria shared(String id) {
                return EventCriteria.havingTags("shared", id);
            }
        }

        @Test
        void sourcingRoleUsesSourcingBuilderOverSharedBuilder() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(
                    SourcingOverridesSharedForSourcingRoleOnly.class, Object.class, configuration);
            assertEquals(EventCriteria.havingTags("sourcing", "id"),
                         sourcingResolver.resolve("id", new StubProcessingContext()));
        }

        @Test
        void appendRoleFallsBackToSharedBuilderWhenNoAppendBuilderExists() {
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(
                    SourcingOverridesSharedForSourcingRoleOnly.class, Object.class, configuration);
            assertEquals(EventCriteria.havingTags("shared", "id"),
                         appendResolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity
        static class AppendOverridesSharedForAppendRoleOnly {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria append(String id) {
                return EventCriteria.havingTags("append", id);
            }

            @SuppressWarnings("unused")
            @EventCriteriaBuilder
            static EventCriteria shared(String id) {
                return EventCriteria.havingTags("shared", id);
            }
        }

        @Test
        void appendRoleUsesAppendBuilderOverSharedBuilder() {
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(
                    AppendOverridesSharedForAppendRoleOnly.class, Object.class, configuration);
            assertEquals(EventCriteria.havingTags("append", "id"),
                         appendResolver.resolve("id", new StubProcessingContext()));
        }

        @Test
        void sourcingRoleFallsBackToSharedBuilderWhenNoSourcingBuilderExists() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(
                    AppendOverridesSharedForAppendRoleOnly.class, Object.class, configuration);
            assertEquals(EventCriteria.havingTags("shared", "id"),
                         sourcingResolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity
        static class AllThreeRoleSpecificBuilders {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcing(String id) {
                return EventCriteria.havingTags("sourcing", id);
            }

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria append(String id) {
                return EventCriteria.havingTags("append", id);
            }
        }

        @Test
        void sourcingAndAppendBuildersCanCoexistForTheSameIdentifierType() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(
                    AllThreeRoleSpecificBuilders.class, Object.class, configuration);
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(
                    AllThreeRoleSpecificBuilders.class, Object.class, configuration);

            assertEquals(EventCriteria.havingTags("sourcing", "id"),
                         sourcingResolver.resolve("id", new StubProcessingContext()));
            assertEquals(EventCriteria.havingTags("append", "id"),
                         appendResolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity(tagKey = "fallbackTagKey")
        static class NoBuildersAtAll {

        }

        @Test
        void bothRolesFallBackToTagKeyWhenNoBuilderMatches() {
            var sourcingResolver = new AnnotationBasedSourcingCriteriaResolver<>(NoBuildersAtAll.class, Object.class,
                                                                                  configuration);
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(NoBuildersAtAll.class, Object.class,
                                                                              configuration);

            EventCriteria expected = EventCriteria.havingTags("fallbackTagKey", "id");
            assertEquals(expected, sourcingResolver.resolve("id", new StubProcessingContext()));
            assertEquals(expected, appendResolver.resolve("id", new StubProcessingContext()));
        }
    }

    @Nested
    class SourcingCriteriaInjection {

        @EventSourcedEntity
        static class AccountEntity {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcingCriteria(String id) {
                return EventCriteria.havingTags("account", id);
            }

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id, EventCriteria sourcingCriteria) {
                return sourcingCriteria.or(EventCriteria.havingTags("balance-decrease", id));
            }
        }

        @Test
        void appendBuilderReceivesExactSourcingCriteriaResolvedForTheSameIdentifier() {
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(AccountEntity.class, Object.class,
                                                                              configuration);

            EventCriteria result = appendResolver.resolve("account-1", new StubProcessingContext());

            EventCriteria expected = EventCriteria.havingTags("account", "account-1")
                                                  .or(EventCriteria.havingTags("balance-decrease", "account-1"));
            assertThat(result.flatten()).containsExactlyInAnyOrderElementsOf(expected.flatten());
        }

        @EventSourcedEntity
        static class EntityWithoutSourcingBuilder {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id, EventCriteria sourcingCriteria) {
                return sourcingCriteria;
            }
        }

        @Test
        void sourcingCriteriaParameterReceivesTheTagFallbackWhenNoSourcingBuilderExists() {
            var appendResolver = new AnnotationBasedAppendCriteriaResolver<>(EntityWithoutSourcingBuilder.class,
                                                                              Object.class, configuration);

            EventCriteria result = appendResolver.resolve("id", new StubProcessingContext());

            assertEquals(EventCriteria.havingTags("EntityWithoutSourcingBuilder", "id"), result);
        }

        @Test
        void atMostOneEventCriteriaParameterIsAllowed() {
            var exception = assertThatThrownBy(
                    () -> new AnnotationBasedAppendCriteriaResolver<>(EntityWithTwoSourcingCriteriaParameters.class,
                                                                       Object.class, configuration)
            ).isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains(
                    "must declare at most one EventCriteria parameter");
        }

        @EventSourcedEntity
        static class EntityWithTwoSourcingCriteriaParameters {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id, EventCriteria first, EventCriteria second) {
                return first.or(second);
            }
        }
    }

    @Nested
    class ParameterInjection {

        @EventSourcedEntity
        static class EntityInjectingProcessingContext {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcingCriteria(String id, ProcessingContext context) {
                return EventCriteria.havingTags(context != null ? "context-present" : "context-absent", id);
            }

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id, ProcessingContext context) {
                return EventCriteria.havingTags(context != null ? "context-present" : "context-absent", id);
            }
        }

        @Test
        void sourcingBuilderCanInjectProcessingContext() {
            var resolver = new AnnotationBasedSourcingCriteriaResolver<>(EntityInjectingProcessingContext.class,
                                                                          Object.class, configuration);
            assertEquals(EventCriteria.havingTags("context-present", "id"),
                         resolver.resolve("id", new StubProcessingContext()));
        }

        @Test
        void appendBuilderCanInjectProcessingContext() {
            var resolver = new AnnotationBasedAppendCriteriaResolver<>(EntityInjectingProcessingContext.class,
                                                                        Object.class, configuration);
            assertEquals(EventCriteria.havingTags("context-present", "id"),
                         resolver.resolve("id", new StubProcessingContext()));
        }

        @EventSourcedEntity
        static class EntityInjectingConfigurationComponent {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id, MessageTypeResolver messageTypeResolver) {
                return messageTypeResolver != null
                        ? EventCriteria.havingTags("component-present", id)
                        : EventCriteria.havingTags("component-absent", id);
            }
        }

        @Test
        void appendBuilderCanInjectConfiguredComponent() {
            MessageTypeResolver messageTypeResolver = clazz -> Optional.empty();
            when(configuration.getOptionalComponent(MessageTypeResolver.class))
                    .thenReturn(Optional.of(messageTypeResolver));

            var resolver = new AnnotationBasedAppendCriteriaResolver<>(EntityInjectingConfigurationComponent.class,
                                                                        Object.class, configuration);
            assertEquals(EventCriteria.havingTags("component-present", "id"),
                         resolver.resolve("id", new StubProcessingContext()));
        }
    }

    @Nested
    class Validation {

        @Test
        void methodWithBothSourcingAndAppendAnnotationsIsRejected() {
            var exception = assertThatThrownBy(
                    () -> new AnnotationBasedSourcingCriteriaResolver<>(EntityWithMultipleAnnotations.class,
                                                                         Object.class, configuration)
            ).isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains(
                    "must not be annotated with more than one of @EventCriteriaBuilder, @SourcingCriteriaBuilder, "
                            + "@AppendCriteriaBuilder");
        }

        @EventSourcedEntity
        static class EntityWithMultipleAnnotations {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            @AppendCriteriaBuilder
            static EventCriteria criteria(String id) {
                return EventCriteria.havingTags("criteria", id);
            }
        }

        @Test
        void duplicateSourcingCriteriaBuildersForSameIdentifierTypeAreRejected() {
            var exception = assertThatThrownBy(
                    () -> new AnnotationBasedSourcingCriteriaResolver<>(EntityWithDuplicateSourcingBuilders.class,
                                                                         Object.class, configuration)
            ).isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains("Multiple @SourcingCriteriaBuilder methods found");
        }

        @EventSourcedEntity
        static class EntityWithDuplicateSourcingBuilders {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcingOne(String id) {
                return EventCriteria.havingAnyTag();
            }

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            static EventCriteria sourcingTwo(String id) {
                return EventCriteria.havingAnyTag();
            }
        }

        @Test
        void duplicateAppendCriteriaBuildersForSameIdentifierTypeAreRejected() {
            var exception = assertThatThrownBy(
                    () -> new AnnotationBasedAppendCriteriaResolver<>(EntityWithDuplicateAppendBuilders.class,
                                                                       Object.class, configuration)
            ).isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains("Multiple @AppendCriteriaBuilder methods found");
        }

        @EventSourcedEntity
        static class EntityWithDuplicateAppendBuilders {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendOne(String id) {
                return EventCriteria.havingAnyTag();
            }

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendTwo(String id) {
                return EventCriteria.havingAnyTag();
            }
        }

        @Test
        void nonStaticSourcingCriteriaBuilderIsRejected() {
            var exception = assertThatThrownBy(
                    () -> new AnnotationBasedSourcingCriteriaResolver<>(EntityWithNonStaticSourcingBuilder.class,
                                                                         Object.class, configuration)
            ).isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains("must be static");
        }

        @EventSourcedEntity
        static class EntityWithNonStaticSourcingBuilder {

            @SuppressWarnings("unused")
            @SourcingCriteriaBuilder
            EventCriteria sourcingCriteria(String id) {
                return EventCriteria.havingAnyTag();
            }
        }

        @Test
        void nullReturningAppendCriteriaBuilderThrowsAtResolution() {
            var resolver = new AnnotationBasedAppendCriteriaResolver<>(EntityWithNullReturningAppendBuilder.class,
                                                                        Object.class, configuration);
            var exception = assertThatThrownBy(() -> resolver.resolve("id", new StubProcessingContext()))
                    .isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains(
                    "The @AppendCriteriaBuilder method returned null");
        }

        @EventSourcedEntity
        static class EntityWithNullReturningAppendBuilder {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id) {
                return null;
            }
        }

        @Test
        void unresolvableExtraParameterOnAppendCriteriaBuilderIsRejected() {
            var exception = assertThatThrownBy(
                    () -> new AnnotationBasedAppendCriteriaResolver<>(EntityWithUnknownAppendParameter.class,
                                                                       Object.class, configuration)
            ).isInstanceOf(IllegalArgumentException.class).actual();
            assertThat(exception.getMessage()).contains(
                    "Method annotated with @AppendCriteriaBuilder declared a parameter which is not a component");
        }

        @EventSourcedEntity
        static class EntityWithUnknownAppendParameter {

            @SuppressWarnings("unused")
            @AppendCriteriaBuilder
            static EventCriteria appendCriteria(String id, Integer notAComponent) {
                return EventCriteria.havingAnyTag();
            }
        }
    }
}
