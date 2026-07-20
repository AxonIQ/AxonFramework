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

package org.axonframework.modelling.entity.child;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStreamTestUtils;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.modelling.entity.ChildEntityNotFoundException;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.child.mock.RecordingChildEntity;
import org.axonframework.modelling.entity.child.mock.RecordingEntity;
import org.axonframework.modelling.entity.child.mock.RecordingParentEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MapEntityChildMetamodel}.
 *
 * @author Steven van Beelen
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MapEntityChildMetamodelTest {

    private static final QualifiedName COMMAND = new QualifiedName("Command");
    private static final QualifiedName EVENT = new QualifiedName("Event");
    private static final String COMMAND_MATCHING_ID = "1337";
    private static final String COMMAND_SKIPPING_ID = "123";
    private static final String EVENT_MATCHING_ID = "42";
    private static final String EVENT_SKIPPING_ID = "456";

    @Mock
    private EntityMetamodel<RecordingChildEntity> childEntityMetamodel;
    @Mock
    private ChildEntityFieldDefinition<RecordingParentEntity, Map<String, RecordingChildEntity>> childEntityFieldDefinition;
    private RecordingParentEntity parentEntity;

    private MapEntityChildMetamodel<String, RecordingChildEntity, RecordingParentEntity> testSubject;

    @BeforeEach
    void setUp() {
        parentEntity = new RecordingParentEntity();

        testSubject = MapEntityChildMetamodel.<String, RecordingChildEntity, RecordingParentEntity>forEntityModel(
                                                     RecordingParentEntity.class, childEntityMetamodel
                                             )
                                             .childEntityFieldDefinition(childEntityFieldDefinition)
                                             .commandTargetResolver((candidates, command, context) -> {
                                                 return candidates.stream()
                                                                  .filter(
                                                                          c -> c.getId()
                                                                                .contains(COMMAND_MATCHING_ID)
                                                                  )
                                                                  .findFirst()
                                                                  .orElse(null);
                                             })
                                             .eventTargetMatcher(
                                                     (e, event, context) -> e.getId().contains(EVENT_MATCHING_ID)
                                             )
                                             .build();
    }

    @Nested
    @DisplayName("Command handling")
    public class CommandHandling {

        private final CommandMessage commandMessage = new GenericCommandMessage(new MessageType(COMMAND), "myPayload");
        private final ProcessingContext context = StubProcessingContext.forMessage(commandMessage);

        @BeforeEach
        void setUp() {
            when(childEntityMetamodel.handleInstance(any(), any(), any())).thenAnswer(answer -> {
                RecordingChildEntity child = answer.getArgument(1);
                return MessageStream.just(
                        new GenericCommandResultMessage(new MessageType(String.class), child.getId() + "-result")
                );
            });
        }

        @Test
        void commandForChildIsForwardedToMatchingChildEntity() {
            RecordingChildEntity entityToBeFound = new RecordingChildEntity(COMMAND_MATCHING_ID);
            RecordingChildEntity entityToBeSkipped = new RecordingChildEntity(COMMAND_SKIPPING_ID);
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(
                    Map.of("found", entityToBeFound, "skipped", entityToBeSkipped)
            );

            var result = testSubject.handle(commandMessage, parentEntity, context);
            assertThat(result.asCompletableFuture().join().message().payload()).isEqualTo("1337-result");

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityMetamodel).handleInstance(commandMessage, entityToBeFound, context);
            verify(childEntityMetamodel, times(0)).handleInstance(commandMessage, entityToBeSkipped, context);
        }

        @Test
        void commandResultsInFailedMessageStreamWhenChildEntityIsNotFound() {
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(null);

            MessageStreamTestUtils.assertCompletedExceptionally(
                    testSubject.handle(commandMessage, parentEntity, context),
                    ChildEntityNotFoundException.class,
                    "No available child entity found for command of type [Command#0.0.1]. State of parent entity ["
            );
        }

        @Test
        void commandResultsInFailedMessageStreamWhenNoChildEntityMatches() {
            RecordingChildEntity entityToBeSkipped = new RecordingChildEntity("l0ser");
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(Map.of("skipped", entityToBeSkipped));

            MessageStreamTestUtils.assertCompletedExceptionally(
                    testSubject.handle(commandMessage, parentEntity, context),
                    ChildEntityNotFoundException.class,
                    "No available child entity found for command of type [Command#0.0.1]. State of parent entity ["
            );
        }
    }

    @Test
    void supportedCommandsIsSameAsChildEntity() {
        when(childEntityMetamodel.supportedCommands()).thenReturn(Set.of(COMMAND));

        assertThat(testSubject.supportedCommands()).isEqualTo(Set.of(COMMAND));
    }

    @Test
    void entityTypeIsSameAsChildEntity() {
        when(childEntityMetamodel.entityType()).thenReturn(RecordingChildEntity.class);

        assertThat(testSubject.entityType()).isEqualTo(RecordingChildEntity.class);
    }

    @Test
    void returnsEntityModel() {
        assertThat(testSubject.entityMetamodel()).isEqualTo(childEntityMetamodel);
    }

    @Nested
    @DisplayName("Event handling")
    public class EventHandling {

        private final EventMessage event = new GenericEventMessage(new MessageType(EVENT), "myPayload");
        private final ProcessingContext context = StubProcessingContext.forMessage(event);

        @BeforeEach
        void setUp() {
            when(childEntityMetamodel.evolve(any(), any(), any())).thenAnswer(answ -> {
                RecordingChildEntity child = answ.getArgument(0);
                EventMessage event = answ.getArgument(1);
                return child.evolve("child evolve: " + event.payload());
            });
            when(childEntityFieldDefinition.evolveParentBasedOnChildInput(any(), any())).thenAnswer(answ -> {
                RecordingParentEntity parent = answ.getArgument(0);
                Map<String, RecordingChildEntity> children = answ.getArgument(1);
                return parent.evolve(
                        "parent evolve: [" + children.values().stream().map(RecordingEntity::getEvolves)
                                                     .map(Objects::toString)
                                                     .reduce((a, b) -> a + "," + b).orElse("") + "]");
            });
        }

        @Test
        void doesNotEvolveEntityWhenChildEntityIsNotFound() {
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(null);

            RecordingParentEntity result = testSubject.evolve(parentEntity, event, context);

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityFieldDefinition, never()).evolveParentBasedOnChildInput(any(), any());

            assertThat(result).isEqualTo(parentEntity);
            assertThat(parentEntity.getEvolves()).isEmpty();
        }

        @Test
        void evolvesChildEntityAndParentEntityWhenChildEntityIsFound() {
            RecordingChildEntity childEntity = new RecordingChildEntity("42");
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(Map.of("theKey", childEntity));

            RecordingParentEntity result = testSubject.evolve(parentEntity, event, context);

            assertThat(result.getEvolves().getFirst()).isEqualTo("parent evolve: [[child evolve: myPayload]]");
            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityFieldDefinition).evolveParentBasedOnChildInput(
                    eq(parentEntity),
                    argThat(m -> m.get("theKey").getEvolves().contains("child evolve: myPayload"))
            );
            verify(childEntityMetamodel).evolve(childEntity, event, context);
        }

        @Test
        void evolvesOnlyMatchingChildEvolvesAndPreservesOriginalKeys() {
            RecordingChildEntity matchingEntityOne = new RecordingChildEntity(EVENT_MATCHING_ID + "-1");
            RecordingChildEntity matchingEntityTwo = new RecordingChildEntity(EVENT_MATCHING_ID + "-2");
            RecordingChildEntity nonMatchingEntity1 = new RecordingChildEntity(EVENT_SKIPPING_ID + "-3");
            RecordingChildEntity nonMatchingEntity2 = new RecordingChildEntity(EVENT_SKIPPING_ID + "-4");
            Map<String, RecordingChildEntity> children = new LinkedHashMap<>();
            children.put("one", matchingEntityOne);
            children.put("two", nonMatchingEntity2);
            children.put("three", matchingEntityTwo);
            children.put("four", nonMatchingEntity1);
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(children);

            testSubject.evolve(parentEntity, event, context);

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityMetamodel).evolve(matchingEntityOne, event, context);
            verify(childEntityMetamodel).evolve(matchingEntityTwo, event, context);
            verify(childEntityMetamodel, times(0)).evolve(nonMatchingEntity1, event, context);
            verify(childEntityMetamodel, times(0)).evolve(nonMatchingEntity2, event, context);
            verify(childEntityFieldDefinition).evolveParentBasedOnChildInput(
                    eq(parentEntity),
                    argThat(
                            m -> m.keySet().equals(Set.of("one", "two", "three", "four"))
                                    && m.get("one").getEvolves().contains("child evolve: myPayload")
                                    && m.get("three").getEvolves().contains("child evolve: myPayload")
                                    && m.get("two") == nonMatchingEntity2
                                    && m.get("four") == nonMatchingEntity1
                    ));
        }

        @Test
        void evolvedChildEntitiesToNullAreRemovedFromParentWhileOtherKeysAreUnaffected() {
            RecordingChildEntity evolvingEntity = new RecordingChildEntity(EVENT_MATCHING_ID);
            RecordingChildEntity untouchedEntity = new RecordingChildEntity(EVENT_SKIPPING_ID);
            Map<String, RecordingChildEntity> children = new LinkedHashMap<>();
            children.put("removeMe", evolvingEntity);
            children.put("keepMe", untouchedEntity);
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(children);

            // Reset the standard evolve, to evolve to null
            reset(childEntityMetamodel);
            when(childEntityMetamodel.evolve(any(), any(), any())).thenAnswer(answ -> null);

            testSubject.evolve(parentEntity, event, context);

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityFieldDefinition).evolveParentBasedOnChildInput(
                    eq(parentEntity),
                    argThat(m -> !m.containsKey("removeMe") && m.get("keepMe") == untouchedEntity && m.size() == 1)
            );
            verify(childEntityMetamodel).evolve(evolvingEntity, event, context);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    @Nested
    @DisplayName("Builder verification")
    public class BuilderVerification {

        @Mock
        private ChildEntityFieldDefinition<RecordingParentEntity, Map<Object, RecordingChildEntity>> mockEntityFieldDefinition;
        @Mock
        private CommandTargetResolver<RecordingChildEntity> mockCommandTargetResolver;
        @Mock
        private EventTargetMatcher<RecordingChildEntity> mockEventTargetMatcher;

        @Test
        void canNotStartBuilderWithNullEntityModel() {
            assertThatThrownBy(() -> MapEntityChildMetamodel.forEntityModel(RecordingParentEntity.class, null))
                    .isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void canNotStartBuilderWithNullParentEntityClass() {
            assertThatThrownBy(() -> MapEntityChildMetamodel.forEntityModel(null, childEntityMetamodel))
                    .isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void canNotCompleteBuilderWithoutFieldDefinition() {
            var builder = MapEntityChildMetamodel.forEntityModel(RecordingParentEntity.class, childEntityMetamodel)
                                                 .commandTargetResolver(mockCommandTargetResolver)
                                                 .eventTargetMatcher(mockEventTargetMatcher);
            assertThatThrownBy(builder::build).isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void canNotCompleteBuilderWithoutCommandTargetResolver() {
            var builder = MapEntityChildMetamodel.forEntityModel(RecordingParentEntity.class, childEntityMetamodel)
                                                 .childEntityFieldDefinition(mockEntityFieldDefinition)
                                                 .eventTargetMatcher(mockEventTargetMatcher);
            assertThatThrownBy(builder::build).isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void canNotCompleteBuilderWithoutEventTargetMatcher() {
            var builder = MapEntityChildMetamodel.forEntityModel(RecordingParentEntity.class, childEntityMetamodel)
                                                 .childEntityFieldDefinition(mockEntityFieldDefinition)
                                                 .commandTargetResolver(mockCommandTargetResolver);
            assertThatThrownBy(builder::build).isInstanceOf(AxonConfigurationException.class);
        }
    }
}
