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
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.junit.jupiter.api.*;

import java.util.UUID;

/**
 * Confirms the single-child routing and sourcing behaviour holds with {@link UUID} identifiers and a
 * {@link EventSourcedEntity#tagKey() tagKey} that differs from the identifier field name, mirroring a typical
 * migrated aggregate.
 * <p>
 * The parent is keyed by {@code UUID} and tagged with key {@code "myAggregate"}. The single child carries no
 * {@code routingKey}, so it receives every sourced event. The child event handler flips the child to "collecting",
 * and the child command handler rejects a second save, so the command outcome reveals whether the child was evolved.
 */
class UuidChildEntityEventSourcingTest {

    private static final UUID PARENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    record SaveCollectedData(@TargetEntityId UUID parentId, UUID childId) {

    }

    record ParentInitialized(@EventTag(key = "myAggregate") UUID parentId) {

    }

    record ChildAdded(@EventTag(key = "myAggregate") UUID parentId, UUID childId) {

    }

    // Child-targeted event carrying the parent tag, so it is sourced into the parent stream.
    record CollectionStarted(@EventTag(key = "myAggregate") UUID parentId, UUID childId) {

    }

    // Child-targeted event tagged only with its own id, so it is never sourced into the parent stream.
    record CollectionStartedUntagged(UUID parentId, @EventTag(key = "childId") UUID childId) {

    }

    record DataCollected(@EventTag(key = "myAggregate") UUID parentId, UUID childId) {

    }

    @EventSourcedEntity(tagKey = "myAggregate")
    static class ParentAggregate {

        @SuppressWarnings("unused")
        private UUID parentId;

        @EntityMember
        private ChildEntity child;

        @EventSourcingHandler
        void on(ParentInitialized event) {
            this.parentId = event.parentId();
        }

        @EventSourcingHandler
        void on(ChildAdded event) {
            this.child = new ChildEntity(event.childId());
        }

        @EntityCreator
        protected ParentAggregate(@InjectEntityId UUID parentId) {
            this.parentId = parentId;
        }
    }

    static class ChildEntity {

        @SuppressWarnings("unused")
        private final UUID childId;
        private boolean collecting;

        ChildEntity(UUID childId) {
            this.childId = childId;
        }

        @CommandHandler
        public void handle(SaveCollectedData cmd, EventAppender appender) {
            if (!collecting) {
                throw new IllegalStateException("Child is not collecting");
            }
            appender.append(new DataCollected(cmd.parentId(), cmd.childId()));
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

    AxonTestFixture fixture() {
        return AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(
                EventSourcedEntityModule.autodetected(UUID.class, ParentAggregate.class)));
    }

    // then: with UUID ids and no routingKey, a child event carrying the parent tag evolves the child,
    // so the child is "collecting" and the command succeeds.
    @Test
    void childEventWithParentTagEvolvesChild() {
        fixture().given()
                 .events(new ParentInitialized(PARENT_ID),
                         new ChildAdded(PARENT_ID, CHILD_ID),
                         new CollectionStarted(PARENT_ID, CHILD_ID))
                 .when()
                 .command(new SaveCollectedData(PARENT_ID, CHILD_ID))
                 .then()
                 .success()
                 .events(new DataCollected(PARENT_ID, CHILD_ID));
    }

    // then: a child event without the parent tag is never sourced, so the child stays un-evolved
    // and the command is rejected.
    @Test
    void childEventWithoutParentTagIsNeverSourced() {
        fixture().given()
                 .events(new ParentInitialized(PARENT_ID),
                         new ChildAdded(PARENT_ID, CHILD_ID),
                         new CollectionStartedUntagged(PARENT_ID, CHILD_ID))
                 .when()
                 .command(new SaveCollectedData(PARENT_ID, CHILD_ID))
                 .then()
                 .exception(IllegalStateException.class);
    }
}
