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
package events.eventstoreinternals.appendcriteria.annotation;

// tag::metadata-append-criteria[]
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;

public class UsernameCommandHandler {

    @CommandHandler
    void handle(RegisterUsername command,
                @MetadataValue(value = "tenantId", required = true) String tenantId,
                EventAppender eventAppender) {
        eventAppender.append(new UsernameRegistered(tenantId, command.username()));
    }

    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(
            RegisterUsername command,
            EventCriteria sourcingCriteria,
            @MetadataValue(value = "tenantId", required = true) String tenantId
    ) {
        return EventCriteria.havingTags(
                "tenantId", tenantId,
                "username", command.username()
        );
    }
}
// end::metadata-append-criteria[]

record RegisterUsername(String username) {
}

record UsernameRegistered(
        @EventTag(key = "tenantId") String tenantId,
        @EventTag(key = "username") String username
) {
}
