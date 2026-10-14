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

package org.axonframework.extension.springboot.test;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.util.UUID;

/**
 * Verifies that the Spring {@link EventSourced} stereotype with a {@link UUID} identifier registers a single
 * {@link EntityMember} child correctly, and that the child's {@link EventSourcingHandler} is invoked for events
 * sourced into the parent stream.
 * <p>
 * Mirrors a migrated aggregate: the parent is keyed by {@code UUID} and tagged with a {@code tagKey} that differs
 * from the identifier field name, the child carries no {@code routingKey} (so it receives every sourced event), and
 * the child rejects a second save once already collecting, making the child's sourced state observable through the
 * command outcome.
 */
@AxonSpringBootTest(
        classes = SpringEventSourcedChildEntityTest.TestApplication.class,
        properties = "axon.axonserver.enabled=false"
)
class SpringEventSourcedChildEntityTest {

    private static final UUID CASE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Autowired
    AxonTestFixture fixture;

    // then: with a UUID id and a single child without a routingKey, a child event carrying the parent tag
    // evolves the child, so the follow-up command succeeds.
    @Test
    void childEventWithParentTagEvolvesChild() {
        fixture.given()
               .events(new CaseOpened(CASE_ID),
                       new TaskAdded(CASE_ID, TASK_ID),
                       new CollectionStarted(CASE_ID, TASK_ID))
               .when()
               .command(new SaveCollectedData(CASE_ID, TASK_ID))
               .then()
               .success()
               .events(new DataCollected(CASE_ID, TASK_ID));
    }

    // then: a child event that lacks the parent tag is never sourced, so the child stays un-evolved and the
    // command is rejected.
    @Test
    void childEventWithoutParentTagIsNeverSourced() {
        fixture.given()
               .events(new CaseOpened(CASE_ID),
                       new TaskAdded(CASE_ID, TASK_ID),
                       new CollectionStartedUntagged(CASE_ID, TASK_ID))
               .when()
               .command(new SaveCollectedData(CASE_ID, TASK_ID))
               .then()
               .exception(IllegalStateException.class);
    }

    record SaveCollectedData(@TargetEntityId UUID caseId, UUID taskId) {

    }

    record CaseOpened(@EventTag(key = "caseId") UUID caseId) {

    }

    record TaskAdded(@EventTag(key = "caseId") UUID caseId, UUID taskId) {

    }

    record CollectionStarted(@EventTag(key = "caseId") UUID caseId, UUID taskId) {

    }

    record CollectionStartedUntagged(UUID caseId, @EventTag(key = "taskId") UUID taskId) {

    }

    record DataCollected(@EventTag(key = "caseId") UUID caseId, UUID taskId) {

    }

    @EventSourced(idType = UUID.class, tagKey = "caseId")
    static class CaseAggregate {

        @SuppressWarnings("unused")
        private UUID caseId;

        @EntityMember
        private Task task;

        @EventSourcingHandler
        void on(CaseOpened event) {
            this.caseId = event.caseId();
        }

        @EventSourcingHandler
        void on(TaskAdded event) {
            this.task = new Task(event.taskId());
        }

        @EntityCreator
        CaseAggregate(@InjectEntityId UUID caseId) {
            this.caseId = caseId;
        }
    }

    static class Task {

        @SuppressWarnings("unused")
        private final UUID taskId;
        private boolean collecting;

        Task(UUID taskId) {
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
        void on(CollectionStarted event) {
            this.collecting = true;
        }

        @EventSourcingHandler
        void on(CollectionStartedUntagged event) {
            this.collecting = true;
        }
    }

    @EnableAutoConfiguration
    @Import(CaseAggregate.class)
    static class TestApplication {

    }
}
