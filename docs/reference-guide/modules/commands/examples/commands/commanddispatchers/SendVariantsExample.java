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
package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;

class SendVariantsExample {

    private CommandGateway commandGateway;

    void sendWithMetadata(Object command) {
        // tag::send-with-metadata[]
        // Send with metadata
        CommandResult result = commandGateway.send(command, Metadata.with("userId", "12345"));

        // end::send-with-metadata[]
    }

    void sendWithContext(Object command, ProcessingContext processingContext) {
        // tag::send-with-context[]
        // Send with ProcessingContext (when dispatching from within a handler)
        CommandResult result = commandGateway.send(command, processingContext);

        // end::send-with-context[]
    }

    void sendWithMetadataAndContext(Object command, Metadata metadata, ProcessingContext processingContext) {
        // tag::send-with-metadata-and-context[]
        // Send with both metadata and ProcessingContext
        CommandResult result = commandGateway.send(command, metadata, processingContext);

        // end::send-with-metadata-and-context[]
    }

    void sendAndGetFuture(Object command) {
        // tag::send-and-get-future[]
        // Send and get CompletableFuture directly
        CompletableFuture<String> future = commandGateway.send(command, String.class);
        // end::send-and-get-future[]
    }
}
