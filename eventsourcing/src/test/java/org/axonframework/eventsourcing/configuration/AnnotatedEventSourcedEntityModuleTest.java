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

package org.axonframework.eventsourcing.configuration;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.annotation.CriteriaResolverDefinition;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcedEntityFactoryDefinition;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.handler.EntityLifecycleHandler;
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.Repository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Test class validating the {@link AnnotatedEventSourcedEntityModule}.
 *
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @author Simon Zambrovski
 */
@ExtendWith(MockitoExtension.class)
class AnnotatedEventSourcedEntityModuleTest {

    private StubLifecycleRegistry lifecycleRegistry;
    private DefaultComponentRegistry componentRegistry;

    @BeforeEach
    void setUp() {
        lifecycleRegistry = new StubLifecycleRegistry();
        componentRegistry = new DefaultComponentRegistry();
    }

    @Test
    void annotatedEntityThrowsNullPointerExceptionForNullIdentifierType() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> EventSourcedEntityModule.autodetected(null, Course.class));
    }

    @Test
    void annotatedEntityThrowsNullPointerExceptionForNullEntityType() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> EventSourcedEntityModule.autodetected(CourseId.class, null));
    }

    @Test
    void annotatedEntityThrowsIllegalArgumentExceptionForNotAnnotatedEntity() {
        assertThrows(IllegalArgumentException.class,
                     () -> EventSourcedEntityModule.autodetected(CourseId.class, CourseId.class));
    }

    @Test
    void entityNameCombinesIdentifierAndEntityTypeNames() {
        String expectedEntityName = Course.class.getName() + "#" + CourseId.class.getName();

        EventSourcedEntityModule<CourseId, Course> testSubject =
                EventSourcedEntityModule.autodetected(CourseId.class, Course.class);

        assertThat(testSubject.entityName()).isEqualTo(expectedEntityName);
    }

    @Test
    void repositoryConstructsEventSourcingRepositoryForEntityFactory() {

        componentRegistry.registerModule(EventSourcedEntityModule
                                                 .autodetected(CourseId.class, Course.class)
        );

        var parentConfiguration = componentRegistry.build(lifecycleRegistry);
        lifecycleRegistry.start(parentConfiguration);

        StateManager stateManager = parentConfiguration.getComponent(StateManager.class);
        Repository<CourseId, Course> result = stateManager.repository(Course.class, CourseId.class);

        assertThat(result)
                .isNotNull()
                .isInstanceOf(EventSourcingRepository.class);
    }

    @Test
    void customCriteriaResolverIsPresentOnResultingEventSourcingRepository(
        @Captor ArgumentCaptor<EntityLifecycleHandler<CourseId, CustomCriteriaResolverCourse>> handlerCaptor
    ) {
        componentRegistry.registerModule(
                EventSourcedEntityModule.autodetected(CourseId.class, CustomCriteriaResolverCourse.class)
        );
        ComponentDescriptor componentDescriptor = mock(ComponentDescriptor.class);

        var parentConfiguration = componentRegistry.build(lifecycleRegistry);
        lifecycleRegistry.start(parentConfiguration);

        StateManager stateManager = parentConfiguration.getComponent(StateManager.class);
        Repository<CourseId, CustomCriteriaResolverCourse> result = stateManager.repository(CustomCriteriaResolverCourse.class,
                                                                                            CourseId.class);

        assertThat(result).isNotNull()
                          .isInstanceOf(EventSourcingRepository.class);
        result.describeTo(componentDescriptor);

        verify(componentDescriptor).describeProperty(eq("entityLifecycleHandler"), handlerCaptor.capture());

        handlerCaptor.getValue().describeTo(componentDescriptor);

        verify(componentDescriptor).describeProperty(eq("criteriaResolver"), isA(CustomCriteriaResolver.class));
    }

    @Test
    void customEntityFactoryIsPresentOnResultingEventSourcingRepository(
        @Captor ArgumentCaptor<EntityLifecycleHandler<CourseId, CustomCriteriaResolverCourse>> handlerCaptor,
        @Captor ArgumentCaptor<InitializingEntityEvolver<CourseId, CustomCriteriaResolverCourse>> evolverCaptor
    ) {
        ComponentDescriptor componentDescriptor = mock(ComponentDescriptor.class);
        componentRegistry.registerModule(
                EventSourcedEntityModule.autodetected(CourseId.class, CustomEntityFactoryCourse.class)
        );

        var parentConfiguration = componentRegistry.build(lifecycleRegistry);
        lifecycleRegistry.start(parentConfiguration);

        StateManager stateManager = parentConfiguration.getComponent(StateManager.class);
        Repository<CourseId, CustomEntityFactoryCourse> result = stateManager.repository(CustomEntityFactoryCourse.class,
                                                                                         CourseId.class);

        assertThat(result)
                .isNotNull()
                .isInstanceOf(EventSourcingRepository.class);
        result.describeTo(componentDescriptor);

        verify(componentDescriptor).describeProperty(eq("entityLifecycleHandler"), handlerCaptor.capture());

        handlerCaptor.getValue().describeTo(componentDescriptor);

        verify(componentDescriptor).describeProperty(eq("evolver"), evolverCaptor.capture());

        evolverCaptor.getValue().describeTo(componentDescriptor);

        verify(componentDescriptor).describeProperty(eq("entityFactory"), isA(CustomEventSourcedEntityFactory.class));
    }

    @Test
    void metaAnnotatedEventSourcedEntityConstructsAnEventSourcingRepository() {
        var module = EventSourcedEntityModule.autodetected(CourseId.class, MetaAnnotatedCourse.class);
        componentRegistry.registerModule(module);

        var parentConfiguration = componentRegistry.build(lifecycleRegistry);
        lifecycleRegistry.start(parentConfiguration);


        StateManager stateManager = parentConfiguration.getComponent(StateManager.class);
        Repository<CourseId, MetaAnnotatedCourse> result =
                stateManager.repository(MetaAnnotatedCourse.class, CourseId.class);

        assertThat(result)
                .isNotNull()
                .isInstanceOf(EventSourcingRepository.class);
    }

    @Test
    void failsWhenConcreteTypeIsNotSubclassOfEventSourcedEntity() {
        assertThatThrownBy(() -> new AnnotatedEventSourcedEntityModule<>(String.class,
                                                                         PolymorphicEventSourcedEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The declared concrete type [java.lang.String] is not assignable to the entity "
                                    + "type [org.axonframework.eventsourcing.configuration.AnnotatedEventSourcedEntityModuleTest$PolymorphicEventSourcedEntity]. Please ensure the concrete type is a subclass of the entity type.");
    }

    record CourseId() {

    }

    @EventSourcedEntity
    record Course(CourseId id) {

        @EntityCreator
        public Course {
        }
    }

    @EventSourcedEntity(criteriaResolverDefinition = CustomCriteriaResolverDefinition.class)
    record CustomCriteriaResolverCourse(CourseId id) {

        @EntityCreator
        public CustomCriteriaResolverCourse {
        }
    }

    static class CustomCriteriaResolverDefinition implements CriteriaResolverDefinition {

        @Override
        public <E, ID> @NonNull CriteriaResolver<ID> createEventCriteriaResolver(@NonNull Class<E> entityType,
                                                                                 @NonNull Class<ID> idType,
                                                                                 @NonNull Configuration configuration) {
            assertInstanceOf(Configuration.class, configuration);
            return new CustomCriteriaResolver<>();
        }
    }

    private static class CustomCriteriaResolver<ID> implements CriteriaResolver<ID> {

        @NonNull
        @Override
        public EventCriteria resolve(@NonNull ID id, @NonNull ProcessingContext context) {
            return EventCriteria.havingAnyTag();
        }
    }

    @EventSourcedEntity(entityFactoryDefinition = CustomEventSourcedEntityFactoryDefinition.class)
    record CustomEntityFactoryCourse(CourseId id) {

    }

    static class CustomEventSourcedEntityFactoryDefinition
            implements EventSourcedEntityFactoryDefinition<CustomEntityFactoryCourse, CourseId> {

        @Override
        public @NonNull EventSourcedEntityFactory<CourseId, CustomEntityFactoryCourse> createFactory(
                @NonNull Class<CustomEntityFactoryCourse> entityType,
                @NonNull Set<Class<? extends CustomEntityFactoryCourse>> entitySubTypes,
                @NonNull Class<CourseId> idType,
                @NonNull Configuration configuration
        ) {
            return new CustomEventSourcedEntityFactory();
        }
    }

    static class CustomEventSourcedEntityFactory
            implements EventSourcedEntityFactory<CourseId, CustomEntityFactoryCourse> {

        @Override
        public @Nullable CustomEntityFactoryCourse create(
                @NonNull CourseId courseId,
                @Nullable EventMessage firstEventMessage, @NonNull ProcessingContext context) {
            return new CustomEntityFactoryCourse(courseId);
        }
    }

    @MetaAnnotatedEventSourcingEntity
    record MetaAnnotatedCourse(CourseId id) {

        @EntityCreator
        public MetaAnnotatedCourse {
        }
    }

    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @EventSourcedEntity(tagKey = "metaAnnotated")
    public @interface MetaAnnotatedEventSourcingEntity {

    }

    @EventSourcedEntity(concreteTypes = {String.class})
    interface PolymorphicEventSourcedEntity {

    }
}