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
package messagingconcepts.processingcontext;

import java.time.Instant;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

class AccessingCurrentMessage {

    // tag::accessing-current-message[]
    @CommandHandler
    public void handle(MyCommand command, ProcessingContext context) {
        // Retrieve the current message
        Message message = Message.fromContext(context);

        // Cast to specific message type if needed
        CommandMessage commandMessage = (CommandMessage) message;

        // Access message properties
        String messageId = message.identifier();
        Metadata metadata = message.metadata();
        Instant timestamp = ((EventMessage) message).timestamp(); // For events
    }
    // end::accessing-current-message[]
}
