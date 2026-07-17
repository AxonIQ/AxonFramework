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
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStreamTestUtils;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.entity.ChildEntityNotFoundException;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.child.mock.RecordingChildEntity;
import org.axonframework.modelling.entity.child.mock.RecordingParentEntity;
import org.junit.jupiter.api.*;

import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SingleEntityChildMetamodelTest {

    public static final QualifiedName COMMAND = new QualifiedName("Command");
    public static final QualifiedName EVENT = new QualifiedName("Event");

    private final EntityMetamodel<RecordingChildEntity> childEntityMetamodel = mock();
    private final ChildEntityFieldDefinition<RecordingParentEntity, RecordingChildEntity> childEntityFieldDefinition =
            mock();

    private final SingleEntityChildMetamodel<RecordingChildEntity, RecordingParentEntity> testSubject = SingleEntityChildMetamodel
            .forEntityModel(RecordingParentEntity.class, childEntityMetamodel)
            .childEntityFieldDefinition(childEntityFieldDefinition)
            .build();

    private final RecordingParentEntity parentEntity = new RecordingParentEntity();

    @Nested
    @DisplayName("Command handling")
    public class CommandHandling {

        private final CommandMessage commandMessage = new GenericCommandMessage(
                new MessageType(COMMAND), "myPayload"
        );
        private final ProcessingContext context = StubProcessingContext.forMessage(commandMessage);

        @BeforeEach
        void setUp() {
            when(childEntityMetamodel.handleInstance(any(), any(), any())).thenReturn(
                    MessageStream.just(new GenericCommandResultMessage(new MessageType(String.class), "result")));
        }

        @Test
        void commandForChildIsForwardedToFoundChildEntity() {
            RecordingChildEntity entityToBeFound = new RecordingChildEntity();
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(entityToBeFound);

            GenericCommandMessage command = new GenericCommandMessage(new MessageType(COMMAND), "myPayload");

            var result = testSubject.handle(command, parentEntity, context);
            assertEquals("result", result.asCompletableFuture().join().message().payload());

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityMetamodel).handleInstance(command, entityToBeFound, context);
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
        void supportedCommandsIsSameAsChildEntity() {
            when(childEntityMetamodel.supportedCommands()).thenReturn(Set.of(COMMAND));

            assertEquals(Set.of(COMMAND), testSubject.supportedCommands());
        }
    }

    @Test
    void entityTypeIsSameAsChildEntity() {
        when(childEntityMetamodel.entityType()).thenReturn(RecordingChildEntity.class);

        assertEquals(RecordingChildEntity.class, testSubject.entityType());
    }

    @Test
    void returnsEntityModel() {
        assertEquals(childEntityMetamodel, testSubject.entityMetamodel());
    }

    @Nested
    @DisplayName("Event handling")
    public class EventHandling {

        private final EventMessage event = new GenericEventMessage(new MessageType(EVENT), "myPayload");
        private final ProcessingContext context = StubProcessingContext.forMessage(event);


        @Test
        void doesNotEvolveEntityWhenChildEntityIsNotFound() {
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(null);

            RecordingParentEntity result = testSubject.evolve(parentEntity, event, context);

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityFieldDefinition, never()).evolveParentBasedOnChildInput(any(), any());

            assertEquals(parentEntity, result);
            assertTrue(parentEntity.getEvolves().isEmpty());
        }

        @Test
        void evolvesChildEntityAndParentEntityWhenChildEntityIsFound() {
            RecordingChildEntity childEntity = new RecordingChildEntity();
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(childEntity);
            when(childEntityMetamodel.evolve(any(), any(), any())).thenAnswer(answ -> {
                RecordingChildEntity child = answ.getArgument(0);
                EventMessage event = answ.getArgument(1);
                return child.evolve("child evolve: " + event.payload());
            });
            when(childEntityFieldDefinition.evolveParentBasedOnChildInput(any(), any())).thenAnswer(answ -> {
                RecordingParentEntity parent = answ.getArgument(0);
                RecordingChildEntity child = answ.getArgument(1);
                return parent.evolve("parent evolve: " + child.getEvolves());
            });

            RecordingParentEntity result = testSubject.evolve(parentEntity, event, context);

            assertEquals("parent evolve: [child evolve: myPayload]", result.getEvolves().getFirst());

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityFieldDefinition).evolveParentBasedOnChildInput(
                    eq(parentEntity),
                    argThat(a -> a.getEvolves().contains("child evolve: myPayload"))
            );
            verify(childEntityMetamodel).evolve(childEntity, event, context);
        }

        @Test
        void childEntityCanBeEvolvedToNull() {
            RecordingChildEntity childEntity = new RecordingChildEntity();
            when(childEntityFieldDefinition.getChildValue(any())).thenReturn(childEntity);
            when(childEntityMetamodel.evolve(any(), any(), any())).thenReturn(null);
            when(childEntityFieldDefinition.evolveParentBasedOnChildInput(any(), any())).thenAnswer(answ -> {
                RecordingParentEntity parent = answ.getArgument(0);
                RecordingChildEntity child = answ.getArgument(1);
                return parent.evolve("parent evolve: " + child);
            });

            RecordingParentEntity result = testSubject.evolve(parentEntity, event, context);

            assertEquals("parent evolve: null", result.getEvolves().getFirst());

            verify(childEntityFieldDefinition).getChildValue(parentEntity);
            verify(childEntityFieldDefinition).evolveParentBasedOnChildInput(
                    eq(parentEntity),
                    argThat(Objects::isNull)
            );
            verify(childEntityMetamodel).evolve(childEntity, event, context);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    @Nested
    @DisplayName("Builder verification")
    public class BuilderVerification {

        @Test
        void canNotCompleteBuilderWithoutFieldDefinition() {
            var builder = SingleEntityChildMetamodel.forEntityModel(RecordingParentEntity.class,
                                                                    childEntityMetamodel);
            assertThrows(AxonConfigurationException.class, builder::build);
        }

        @Test
        void canNotStartBuilderWithNullParentEntityClass() {
            assertThrows(NullPointerException.class,
                         () -> SingleEntityChildMetamodel.forEntityModel(null, childEntityMetamodel));
        }

        @Test
        void canNotStartBuilderWithNullEntityModel() {
            assertThrows(NullPointerException.class,
                         () -> SingleEntityChildMetamodel.forEntityModel(RecordingParentEntity.class, null));
        }
    }
}