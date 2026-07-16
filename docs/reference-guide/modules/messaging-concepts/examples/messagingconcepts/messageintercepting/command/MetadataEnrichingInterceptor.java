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
package messagingconcepts.messageintercepting.command;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::command-dispatch-metadata[]
public class MetadataEnrichingInterceptor
        implements MessageDispatchInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnDispatch(
            CommandMessage command,
            ProcessingContext context,
            MessageDispatchInterceptorChain<CommandMessage> chain
    ) {
        // Add metadata
        CommandMessage enrichedCommand = command.andMetadata(
            Metadata.with("timestamp", String.valueOf(System.currentTimeMillis()))
        );

        return chain.proceed(enrichedCommand, context);
    }
}
// end::command-dispatch-metadata[]
