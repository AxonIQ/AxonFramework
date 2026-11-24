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
package events.eventstoreinternals.appendcriteria.entity;

// tag::unique-entity-append-criteria[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;

@EventSourcedEntity(tagKey = "userId")
public class User {

    @EntityCreator
    User() {
    }

    @CommandHandler
    static void handle(RegisterUser command, EventAppender eventAppender) {
        eventAppender.append(new UserRegistered(command.userId(), command.username()));
    }

    // Fails the command if any event in the history has this user id or this username.
    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(RegisterUser command) {
        return EventCriteria.havingTags("userId", command.userId())
                            .or(EventCriteria.havingTags("username", command.username()));
    }
}

// Without @TargetEntityId the framework creates the entity without loading it,
// so the event store checks the append criteria from the start of the history.
record RegisterUser(String userId, String username) {
}

record UserRegistered(@EventTag(key = "userId") String userId, @EventTag(key = "username") String username) {
}
// end::unique-entity-append-criteria[]
