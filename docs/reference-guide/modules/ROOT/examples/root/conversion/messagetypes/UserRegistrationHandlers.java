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
package root.conversion.messagetypes;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

import tools.jackson.databind.JsonNode;

class UserRegisteredEvent {

    private final String userId;
    private final String email;

    UserRegisteredEvent(String userId, String email) {
        this.userId = userId;
        this.email = email;
    }

    String getUserId() {
        return userId;
    }

    String getEmail() {
        return email;
    }
}

class User {

    User(String userId, String email) {
    }
}

interface UserRepository {

    void save(User user);
}

class UserProjection {

    private UserRepository userRepository;

    // tag::full-object-handler[]
    @EventHandler //<1>
    public void on(UserRegisteredEvent event) {
        // Receives the full object
        userRepository.save(new User(event.getUserId(), event.getEmail()));
    }
    // end::full-object-handler[]
}

class UserJsonProjection {

    // tag::json-node-handler[]
    @EventHandler(eventName = "com.example.events.UserRegistered") //<1>
    public void on(JsonNode event) {
        // Receives the same event as a JsonNode
        // Must use the fully qualified name (namespace + name)
        String userId = event.get("userId").asString();
        // Process using JSON directly
    }
    // end::json-node-handler[]
}

class UserEventMessageProjection {

    private UserRepository userRepository;

    // tag::converted-payload-handler[]
    @EventHandler
    public void on(EventMessage event) {
        // Works without passing a converter (Axon attached one when loading the event)
        UserRegisteredEvent payload = event.payloadAs(UserRegisteredEvent.class);
        userRepository.save(new User(payload.getUserId(), payload.getEmail()));
    }
    // end::converted-payload-handler[]
}
