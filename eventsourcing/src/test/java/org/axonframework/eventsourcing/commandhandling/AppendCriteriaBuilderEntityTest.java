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

package org.axonframework.eventsourcing.commandhandling;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating which commands an {@link AppendCriteriaBuilder} declared on an annotated event-sourced entity
 * decides: like any command handler interceptor on an entity, the commands of the entity, its subclasses and the child
 * entities it forwards commands to.
 */
class AppendCriteriaBuilderEntityTest {

    private static final List<Class<?>> decided = new CopyOnWriteArrayList<>();

    private AxonConfiguration configuration;
    private CommandGateway commandGateway;

    @BeforeEach
    void setUp() {
        decided.clear();
    }

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Nested
    class BuilderOnTheRootOfAPolymorphicEntity {

        @BeforeEach
        void startProjects() {
            start(EventSourcedEntityModule.autodetected(String.class, Project.class));
        }

        private void createProject() {
            send(new StartProject("p-1"));
            decided.clear();
        }

        @Test
        void decidesACommandOfTheEntityItself() {
            // given
            createProject();

            // when
            send(new RenameProject("p-1"));

            // then
            assertThat(decided).containsExactly(RenameProject.class);
        }

        @Test
        void decidesACommandCreatingTheEntity() {
            // when the static handler runs without an entity instance
            send(new StartProject("p-1"));

            // then
            assertThat(decided).containsExactly(StartProject.class);
        }

        @Test
        void decidesACommandOfASubclass() {
            // given
            createProject();

            // when
            send(new AddTask("p-1", "t-1"));

            // then
            assertThat(decided).containsExactly(AddTask.class);
        }

        @Test
        void decidesACommandForwardedToAChildEntity() {
            // given
            createProject();
            send(new AddTask("p-1", "t-1"));
            decided.clear();

            // when
            send(new CompleteTask("p-1", "t-1"));

            // then
            assertThat(decided).containsExactly(CompleteTask.class);
        }
    }

    @Nested
    class BuildersOnAParentAndAChildEntity {

        @BeforeEach
        void startBoards() {
            start(EventSourcedEntityModule.autodetected(String.class, Board.class));
            send(new CreateBoard("b-1"));
            decided.clear();
        }

        @Test
        void leaveACommandOnlyTheParentDecidesToTheParent() {
            // when
            send(new AddCard("b-1", "c-1"));

            // then
            assertThat(decided).containsExactly(AddCard.class);
        }

        @Test
        void failACommandBothDecide() {
            // given
            send(new AddCard("b-1", "c-1"));

            // when the parent's catch-all and the child's builder both match the forwarded command
            // then at most one builder decides a command, so it fails instead of one silently winning
            assertThatThrownBy(() -> send(new CompleteCard("b-1", "c-1")))
                    .hasStackTraceContaining("already defined them for this command");
        }
    }

    private void start(EventSourcedEntityModule<String, ?> entity) {
        configuration = EventSourcingConfigurer.create().registerEntity(entity).start();
        commandGateway = configuration.getComponent(CommandGateway.class);
    }

    private void send(Object command) {
        commandGateway.send(command).getResultMessage().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static EventCriteria recordDecision(CommandMessage command, EventCriteria sourcingCriteria) {
        decided.add(command.payloadType());
        return sourcingCriteria;
    }

    record StartProject(@TargetEntityId String projectId) {

    }

    record RenameProject(@TargetEntityId String projectId) {

    }

    record AddTask(@TargetEntityId String projectId, String taskId) {

    }

    record CompleteTask(@TargetEntityId String projectId, String taskId) {

    }

    record ProjectChanged(@EventTag(key = "projectId") String projectId) {

    }

    record TaskAdded(@EventTag(key = "projectId") String projectId, String taskId) {

    }

    record TaskCompleted(@EventTag(key = "projectId") String projectId, String taskId) {

    }

    @EventSourcedEntity(tagKey = "projectId", concreteTypes = SoftwareProject.class)
    abstract static class Project {

        @EntityCreator
        static Project create(@InjectEntityId String projectId) {
            return new SoftwareProject();
        }

        @CommandHandler
        static void handle(StartProject command, EventAppender appender) {
            appender.append(new ProjectChanged(command.projectId()));
        }

        @CommandHandler
        void handle(RenameProject command, EventAppender appender) {
            appender.append(new ProjectChanged(command.projectId()));
        }

        // the only builder of the hierarchy
        @AppendCriteriaBuilder
        static EventCriteria appendCriteria(CommandMessage command, EventCriteria sourcingCriteria) {
            return recordDecision(command, sourcingCriteria);
        }
    }

    static class SoftwareProject extends Project {

        @EntityMember(routingKey = "taskId")
        private final List<Task> tasks = new ArrayList<>();

        @CommandHandler
        void handle(AddTask command, EventAppender appender) {
            appender.append(new TaskAdded(command.projectId(), command.taskId()));
        }

        @EventSourcingHandler
        void on(TaskAdded event) {
            tasks.add(new Task(event.taskId()));
        }
    }

    static class Task {

        private final String taskId;

        Task(String taskId) {
            this.taskId = taskId;
        }

        // read by the parent to route child commands
        public String getTaskId() {
            return taskId;
        }

        @CommandHandler
        void handle(CompleteTask command, EventAppender appender) {
            appender.append(new TaskCompleted(command.projectId(), taskId));
        }
    }

    record CreateBoard(@TargetEntityId String boardId) {

    }

    record BoardCreated(@EventTag(key = "boardId") String boardId) {

    }

    record AddCard(@TargetEntityId String boardId, String cardId) {

    }

    record CompleteCard(@TargetEntityId String boardId, String cardId) {

    }

    record CardAdded(@EventTag(key = "boardId") String boardId, String cardId) {

    }

    record CardCompleted(@EventTag(key = "boardId") String boardId, String cardId) {

    }

    @EventSourcedEntity(tagKey = "boardId")
    static class Board {

        @EntityMember(routingKey = "cardId")
        private final List<Card> cards = new ArrayList<>();

        @EntityCreator
        Board(@InjectEntityId String boardId) {
        }

        @CommandHandler
        static void handle(CreateBoard command, EventAppender appender) {
            appender.append(new BoardCreated(command.boardId()));
        }

        @CommandHandler
        void handle(AddCard command, EventAppender appender) {
            appender.append(new CardAdded(command.boardId(), command.cardId()));
        }

        @EventSourcingHandler
        void on(CardAdded event) {
            cards.add(new Card(event.cardId()));
        }

        @AppendCriteriaBuilder
        static EventCriteria everyCommand(CommandMessage command, EventCriteria sourcingCriteria) {
            return recordDecision(command, sourcingCriteria);
        }
    }

    static class Card {

        private final String cardId;

        Card(String cardId) {
            this.cardId = cardId;
        }

        // read by the parent to route child commands
        public String getCardId() {
            return cardId;
        }

        @CommandHandler
        void handle(CompleteCard command, EventAppender appender) {
            appender.append(new CardCompleted(command.boardId(), cardId));
        }

        @AppendCriteriaBuilder
        static EventCriteria completion(CompleteCard command, CommandMessage message, EventCriteria sourcingCriteria) {
            return recordDecision(message, sourcingCriteria);
        }
    }
}
