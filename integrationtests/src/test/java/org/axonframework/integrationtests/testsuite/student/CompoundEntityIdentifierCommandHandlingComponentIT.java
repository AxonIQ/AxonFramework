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

package org.axonframework.integrationtests.testsuite.student;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.commands.AssignMentorCommand;
import org.axonframework.integrationtests.testsuite.student.common.StudentMentorModelIdentifier;
import org.axonframework.integrationtests.testsuite.student.events.MentorAssignedToStudentEvent;
import org.axonframework.integrationtests.testsuite.student.state.StudentMentorAssignment;
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.SimpleEntityEvolvingComponent;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the injection of a compound entity based on a compound id that loads events of two tags.
 *
 * @author Mitchell Herrijgers
 */
public abstract class CompoundEntityIdentifierCommandHandlingComponentIT extends AbstractCommandHandlingStudentIT {
    private final String student1 = createId("student-1");
    private final String student2 = createId("student-2");
    private final String student3 = createId("student-3");

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        EventSourcedEntityModule<StudentMentorModelIdentifier, StudentMentorAssignment> mentorAssignmentSlice =
                EventSourcedEntityModule
                        .declarative(StudentMentorModelIdentifier.class, StudentMentorAssignment.class)
                        .messagingModel((c, model) -> model
                                .entityEvolver(
                                        new SimpleEntityEvolvingComponent<>(
                                                Map.of(
                                                        new QualifiedName(MentorAssignedToStudentEvent.class),
                                                        (entity, event, context) -> {
                                                            MentorAssignedToStudentEvent payload = event.payloadAs(
                                                                    MentorAssignedToStudentEvent.class
                                                            );
                                                            entity.handle(payload);
                                                            return entity;
                                                        }
                                                )
                                        )
                                )
                                .build()
                        )
                        .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(StudentMentorAssignment::new))
                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.either(
                                EventCriteria.havingTags(new Tag("Student", id.menteeId())),
                                EventCriteria.havingTags(new Tag("Student", id.mentorId()))
                                             .andBeingOneOfTypes(MentorAssignedToStudentEvent.class.getName())
                        ))
                        .build();
        return super.testSuiteConfigurer(configurer)
                    .componentRegistry(cr -> cr.registerModule(mentorAssignmentSlice));
    }

    @Test
    void canHandleCommandThatTargetsMultipleModelsViaInjectionOfCompoundModel() {
        registerCommandHandlers(handlerPhase -> handlerPhase.autodetectedCommandHandlingComponent(
                c -> new CompoundModelAnnotatedCommandHandler()
        ));
        startApp();

        verifyMentorLogicForComponent();
    }

    @Test
    void canHandleCommandThatTargetsMultipleModelsViaStatefulCommandHandler() {
        registerCommandHandlers(handlerPhase -> handlerPhase.commandHandler(
                new QualifiedName(AssignMentorCommand.class),
                c -> (command, context) -> {
                    EventAppender eventAppender = EventAppender.forContext(context);
                    AssignMentorCommand payload = command.payloadAs(AssignMentorCommand.class);
                    StateManager state = context.component(StateManager.class);
                    StudentMentorAssignment assignment = state.loadEntity(
                            StudentMentorAssignment.class, payload.modelIdentifier(), context
                    ).join();

                    if (assignment.isMentorHasMentee()) {
                        throw new IllegalArgumentException("Mentor already assigned to a mentee");
                    }
                    if (assignment.isMenteeHasMentor()) {
                        throw new IllegalArgumentException("Mentee already has a mentor");
                    }
                    eventAppender.append(new MentorAssignedToStudentEvent(payload.mentorId(),
                                                                          payload.menteeId()));
                    return MessageStream.just(SUCCESSFUL_COMMAND_RESULT).cast();
                }
        ));
        startApp();

        // Can assign mentor to mentee
        verifyMentorLogicForComponent();
    }

    private void verifyMentorLogicForComponent() {
        // Can assign mentor to mentee
        String result = sendCommand(new AssignMentorCommand(student1, student2), String.class);

        assertEquals("successful", result);

        // But not a second time
        assertThatThrownBy(() -> sendCommand(new AssignMentorCommand(student1, student3)))
            .hasMessageContaining("Mentee already has a mentor");

        // And a third student can't become the mentee of the second, because the second is already a mentor
        assertThatThrownBy(() -> sendCommand(new AssignMentorCommand(student3, student2)))
            .hasMessageContaining("Mentor already assigned to a mentee");

        // But the mentee can become a mentor for a third student
        sendCommand(new AssignMentorCommand(student2, student3));

        // And that third can become a mentor for the first
        sendCommand(new AssignMentorCommand(student3, student1));
    }

    class CompoundModelAnnotatedCommandHandler {

        @CommandHandler
        public String handle(AssignMentorCommand command,
                             @InjectEntity StudentMentorAssignment assignment,
                             EventAppender appender
        ) {
            if (assignment.isMentorHasMentee()) {
                throw new IllegalArgumentException("Mentor already assigned to a mentee");
            }
            if (assignment.isMenteeHasMentor()) {
                throw new IllegalArgumentException("Mentee already has a mentor");
            }

            appender.append(new MentorAssignedToStudentEvent(command.mentorId(), command.menteeId()));
            return "successful";
        }
    }
}