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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.lifecycle.LifecycleHandlerInvocationException;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.CommandAppendCriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.DefaultCorrelationDataProviderRegistry;
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.configuration.EntityMetamodelConfigurationBuilder;
import org.axonframework.modelling.entity.EntityCommandHandlingComponent;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Test class validating the {@link SimpleEventSourcedEntityModule}.
 *
 * @author Steven van Beelen
 */
class SimpleEventSourcedEntityModuleTest {

    private EventSourcedEntityFactory<CourseId, Course> testEntityFactory;
    private CriteriaResolver<CourseId> testCriteriaResolver;
    private EntityMetamodel<Course> testEntityModel;
    private EntityIdResolver<CourseId> testEntityIdResolver;
    private SnapshotPolicy testSnapshotPolicy;
    private AtomicBoolean constructedEntityModel = new AtomicBoolean(false);
    private AtomicBoolean constructedEntityFactory = new AtomicBoolean(false);
    private AtomicBoolean constructedCriteriaResolver = new AtomicBoolean(false);
    private AtomicBoolean constructedEntityIdResolver = new AtomicBoolean(false);
    private AtomicBoolean constructedSnapshotPolicy = new AtomicBoolean(false);

    private EventSourcedEntityModule<CourseId, Course> testSubject;

    @BeforeEach
    void setUp() {
        testEntityFactory = EventSourcedEntityFactory.fromIdentifier(Course::new);
        testCriteriaResolver = (event, context) -> EventCriteria.havingAnyTag();
        testEntityIdResolver = (message, context) -> new CourseId();
        testEntityModel = EntityMetamodel.forEntityType(Course.class)
                                         .entityEvolver((entity, event, context) -> entity)
                                         .instanceCommandHandler(new QualifiedName("instance"),
                                                                 (command, entity, context) -> MessageStream.empty()
                                                                                                            .cast())
                                         .creationalCommandHandler(new QualifiedName("creational"),
                                                                   (command, context) -> MessageStream.empty().cast())
                                         .build();
        testSnapshotPolicy = SnapshotPolicy.afterEvents(5);

        ComponentBuilder<SnapshotPolicy> snapshotPolicyBuilder = c -> {
            constructedSnapshotPolicy.set(true);
            return testSnapshotPolicy;
        };

        testSubject = EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                              .messagingModel((c, b) -> {
                                                  constructedEntityModel.set(true);
                                                  return testEntityModel;
                                              })
                                              .entityFactory(c -> {
                                                  constructedEntityFactory.set(true);
                                                  return testEntityFactory;
                                              })
                                              .criteriaResolver(c -> {
                                                  constructedCriteriaResolver.set(true);
                                                  return testCriteriaResolver;
                                              })
                                              .entityIdResolver(c -> {
                                                  constructedEntityIdResolver.set(true);
                                                  return testEntityIdResolver;
                                              })
                                              .snapshotPolicy(snapshotPolicyBuilder)
                                              .build();
    }

    @Test
    void entityThrowsNullPointerExceptionForNullIdentifierType() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> EventSourcedEntityModule.declarative(null, Course.class));
    }

    @Test
    void entityThrowsNullPointerExceptionForNullEntityType() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> EventSourcedEntityModule.declarative(CourseId.class, null));
    }

    @Test
    void entityFactoryThrowsNullPointerExceptionForNullEntityModel() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                   .messagingModel((EntityMetamodelConfigurationBuilder<Course>) null));
    }

    @Test
    void entityFactoryThrowsNullPointerExceptionForNullEntityFactory() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                   .messagingModel((c, b) -> testEntityModel)
                                                   .entityFactory((ComponentBuilder<EventSourcedEntityFactory<CourseId, Course>>) null));
    }

    @Test
    void criteriaResolverThrowsNullPointerExceptionForNullCriteriaResolver() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                   .messagingModel((c, m) -> testEntityModel)
                                                   .entityFactory(c -> testEntityFactory)
                                                   .criteriaResolver((ComponentBuilder<CriteriaResolver<CourseId>>) null));
    }

    @Test
    void entityEvolverThrowsNullPointerExceptionForNullEntityIdResolver() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                   .messagingModel((c, b) -> testEntityModel)
                                                   .entityFactory(c -> testEntityFactory)
                                                   .criteriaResolver(c -> testCriteriaResolver)
                                                   .entityIdResolver((ComponentBuilder<EntityIdResolver<CourseId>>) null));
    }

    @Test
    void snapshotPolicyThrowsNullPointerExceptionForNullSnapshotPolicy() {
        assertThrows(NullPointerException.class,
            () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                          .messagingModel((c, b) -> testEntityModel)
                                          .entityFactory(c -> testEntityFactory)
                                          .criteriaResolver(c -> testCriteriaResolver)
                                          .snapshotPolicy((ComponentBuilder<SnapshotPolicy>) null)
        );
    }

    @Test
    void appendCriteriaResolverRejectsNullAndDuplicateDefinitions() {
        // given
        EventSourcedEntityModule.OptionalPhase<CourseId, Course> phase =
                EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                        .messagingModel((c, b) -> testEntityModel)
                                        .entityFactory(c -> testEntityFactory)
                                        .criteriaResolver(c -> testCriteriaResolver);
        CommandAppendCriteriaResolver resolver = (command, context, sourcingCriteria) -> sourcingCriteria;

        // when / then
        assertThatThrownBy(() -> phase.appendCriteriaResolver((CommandAppendCriteriaResolver) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("command append criteria resolver");
        phase.appendCriteriaResolver(resolver);
        assertThatThrownBy(() -> phase.appendCriteriaResolver(resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Append criteria have already been configured");
    }

    @Test
    void entityNameCombinesIdentifierAndEntityTypeNames() {
        String expectedEntityName = Course.class.getName() + "#" + CourseId.class.getName();

        assertEquals(expectedEntityName, testSubject.entityName());
    }

    @Test
    void registersAnEventSourcingRepositoryWithTheStateManager() {
        AxonConfiguration configuration = EventSourcingConfigurer.create()
            .componentRegistry(cr -> cr.registerComponent(SnapshotStore.class, c -> mock(SnapshotStore.class)))
            .componentRegistry(cr -> cr.registerModule(testSubject))
            .start();
        Repository<CourseId, Course> result = configuration.getComponent(StateManager.class)
                                                           .repository(Course.class, CourseId.class);

        assertInstanceOf(EventSourcingRepository.class, result);
        assertTrue(constructedEntityFactory.get());
        assertTrue(constructedCriteriaResolver.get());
        assertTrue(constructedEntityModel.get());
        assertTrue(constructedEntityIdResolver.get());
        assertTrue(constructedSnapshotPolicy.get());
    }

    @Test
    void shouldRejectIncompleteConfigurationWhenConfiguringSnapshotting() {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
            .componentRegistry(cr -> cr.registerModule(testSubject));

        assertThatThrownBy(() -> configurer.start())
            .isInstanceOf(LifecycleHandlerInvocationException.class)
            .cause()
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("A SnapshotStore must be configured to use snapshotting.");
    }

    @Test
    void registersAnEntityCommandHandlingComponentWithTheCommandBus() {
        CommandBus commandBus = mock(CommandBus.class);
        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor.
        // Registers the NoOpSequencingPolicy, thus removing the CommandSequencingInterceptor.
        // This ensures we keep the SimpleCommandBus, from which we can retrieve the subscription for validation.
        EventSourcingConfigurer.create()
                               .componentRegistry(cr -> cr.registerComponent(
                                       CorrelationDataProviderRegistry.class,
                                       c -> new DefaultCorrelationDataProviderRegistry())
                               )
                               .componentRegistry(cr -> cr.registerComponent(SequencingPolicy.class,
                                                                             MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                                                             c -> NoOpSequencingPolicy.INSTANCE))
                               .componentRegistry(cr -> cr.registerComponent(SnapshotStore.class, c -> mock(SnapshotStore.class)))
                               .componentRegistry(cr -> cr.registerModule(testSubject)
                                                          .registerComponent(CommandBus.class, c -> commandBus))
                               .start();

        assertTrue(constructedEntityIdResolver.get());

        ArgumentCaptor<CommandHandlingComponent> captor = ArgumentCaptor.forClass(CommandHandlingComponent.class);
        verify(commandBus).subscribe(captor.capture());

        CommandHandlingComponent component = captor.getValue();
        assertInstanceOf(EntityCommandHandlingComponent.class, component);
        assertTrue(component.supportedCommands().contains(new QualifiedName("instance")));
        assertTrue(component.supportedCommands().contains(new QualifiedName("creational")));
    }

    @Nested
    class ConvenienceOverloads {

        @Test
        void acceptsDirectComponentInstancesWithoutConfigurationLambdas() {
            // given / when
            EventSourcedEntityModule<CourseId, Course> module =
                    EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                            .messagingModel(builder -> testEntityModel)
                                            .entityFactory(testEntityFactory)
                                            .criteriaResolver(testCriteriaResolver)
                                            .entityIdResolver(testEntityIdResolver)
                                            .snapshotPolicy(testSnapshotPolicy)
                                            .build();

            AxonConfiguration configuration = EventSourcingConfigurer.create()
                    .componentRegistry(cr -> cr.registerComponent(SnapshotStore.class, c -> mock(SnapshotStore.class)))
                    .componentRegistry(cr -> cr.registerModule(module))
                    .start();

            // then
            Repository<CourseId, Course> result = configuration.getComponent(StateManager.class)
                                                               .repository(Course.class, CourseId.class);
            assertThat(result).isInstanceOf(EventSourcingRepository.class);
            assertThat(module.entityName()).isEqualTo(Course.class.getName() + "#" + CourseId.class.getName());
        }

        @Test
        void convenienceMessagingModelThrowsNullPointerExceptionForNullFactory() {
            //noinspection DataFlowIssue,rawtypes,unchecked
            assertThrows(NullPointerException.class,
                         () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                       .messagingModel(
                                                               (java.util.function.Function<org.axonframework.modelling.entity.EntityMetamodelBuilder<Course>, EntityMetamodel<Course>>) null));
        }

        @Test
        void convenienceEntityFactoryThrowsNullPointerExceptionForNullFactory() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                       .messagingModel(builder -> testEntityModel)
                                                       .entityFactory((EventSourcedEntityFactory<CourseId, Course>) null));
        }

        @Test
        void convenienceCriteriaResolverThrowsNullPointerExceptionForNullResolver() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                       .messagingModel(builder -> testEntityModel)
                                                       .entityFactory(testEntityFactory)
                                                       .criteriaResolver((CriteriaResolver<CourseId>) null));
        }

        @Test
        void convenienceEntityIdResolverThrowsNullPointerExceptionForNullResolver() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                       .messagingModel(builder -> testEntityModel)
                                                       .entityFactory(testEntityFactory)
                                                       .criteriaResolver(testCriteriaResolver)
                                                       .entityIdResolver((EntityIdResolver<CourseId>) null));
        }

        @Test
        void convenienceSnapshotPolicyThrowsNullPointerExceptionForNullPolicy() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> EventSourcedEntityModule.declarative(CourseId.class, Course.class)
                                                       .messagingModel(builder -> testEntityModel)
                                                       .entityFactory(testEntityFactory)
                                                       .criteriaResolver(testCriteriaResolver)
                                                       .snapshotPolicy((SnapshotPolicy) null));
        }
    }

    record CourseId() {

    }

    record Course(CourseId id) {

    }
}
