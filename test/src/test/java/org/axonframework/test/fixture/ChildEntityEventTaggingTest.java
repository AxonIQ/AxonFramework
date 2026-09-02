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

package org.axonframework.test.fixture;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.junit.jupiter.api.*;

/**
 * Proves the second gate a child event must pass, separate from routing: an event only reaches a child's
 * {@link EventSourcingHandler} if it is first sourced into the parent's event stream.
 * <p>
 * The parent is sourced with a single tag, {@code Tag.of(tagKey, id)}, built from {@link EventSourcedEntity#tagKey()}.
 * The child adds nothing to that criteria. So a child-targeted event that does not carry the parent's tag is never
 * loaded, and its handler never runs, even for a single child with no routing key where the event matcher is
 * match-all.
 * <p>
 * Both scenarios use the same parent, the same single child with no {@code routingKey}, and a child event handler that
 * flips the child to "collecting". The only difference is whether the child event carries the parent tag.
 */
class ChildEntityEventTaggingTest {

    private static final String CASE_ID = "case-1";
    private static final String TASK_ID = "task-1";

    // Command handled by the child.
    record SaveCollectedData(@TargetEntityId String caseId, String taskId) {

    }

    // Parent events, tagged with the parent tag key "caseId".
    record CaseOpened(@EventTag(key = "caseId") String caseId) {

    }

    record CollectionTaskAdded(@EventTag(key = "caseId") String caseId, String taskId) {

    }

    // Child event tagged WITH the parent tag -> sourced into the parent stream.
    record CollectionStartedTagged(@EventTag(key = "caseId") String caseId, String taskId) {

    }

    // Child event tagged only with its own task id, NOT the parent tag -> never sourced into the parent stream.
    record CollectionStartedUntagged(String caseId, @EventTag(key = "taskId") String taskId) {

    }

    // Result event appended by the child command handler.
    record DataCollected(@EventTag(key = "caseId") String caseId, String taskId) {

    }

    @EventSourcedEntity(tagKey = "caseId")
    static class Case {

        @SuppressWarnings("unused")
        private String caseId;

        @EntityMember
        private Task task;

        @EventSourcingHandler
        void on(CaseOpened event) {
            this.caseId = event.caseId();
        }

        @EventSourcingHandler
        void on(CollectionTaskAdded event) {
            this.task = new Task(event.taskId());
        }

        @EntityCreator
        protected Case() {
        }
    }

    // Single child with its own command handler and two event-sourcing handlers.
    static class Task {

        @SuppressWarnings("unused")
        private final String taskId;
        private boolean collecting;

        Task(String taskId) {
            this.taskId = taskId;
        }

        @CommandHandler
        public void handle(SaveCollectedData cmd, EventAppender appender) {
            if (!collecting) {
                throw new IllegalStateException("Task is not collecting");
            }
            appender.append(new DataCollected(cmd.caseId(), cmd.taskId()));
        }

        @EventSourcingHandler
        void on(CollectionStartedTagged event) {
            this.collecting = true;
        }

        @EventSourcingHandler
        void on(CollectionStartedUntagged event) {
            this.collecting = true;
        }
    }

    AxonTestFixture fixture() {
        return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                EventSourcedEntityModule.autodetected(String.class, Case.class)));
    }

    // then: the child event carries the parent tag, so it is sourced and evolves the child.
    @Test
    void childEventTaggedWithParentTagReachesChild() {
        fixture().given()
                 .events(new CaseOpened(CASE_ID),
                         new CollectionTaskAdded(CASE_ID, TASK_ID),
                         new CollectionStartedTagged(CASE_ID, TASK_ID))
                 .when()
                 .command(new SaveCollectedData(CASE_ID, TASK_ID))
                 .then()
                 // child was evolved to "collecting", so the command succeeds
                 .success()
                 .events(new DataCollected(CASE_ID, TASK_ID));
    }

    // then: the child event lacks the parent tag, so it is never sourced and the child stays un-evolved.
    @Test
    void childEventWithoutParentTagNeverReachesChild() {
        fixture().given()
                 .events(new CaseOpened(CASE_ID),
                         new CollectionTaskAdded(CASE_ID, TASK_ID),
                         new CollectionStartedUntagged(CASE_ID, TASK_ID))
                 .when()
                 .command(new SaveCollectedData(CASE_ID, TASK_ID))
                 .then()
                 // child never saw the event, so it is still not collecting
                 .exception(IllegalStateException.class);
    }
}
