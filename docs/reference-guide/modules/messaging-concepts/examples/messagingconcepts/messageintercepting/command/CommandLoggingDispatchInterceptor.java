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
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::command-dispatch-logging[]
public class CommandLoggingDispatchInterceptor
        implements MessageDispatchInterceptor<CommandMessage> {

    private static final Logger logger =
            LoggerFactory.getLogger(CommandLoggingDispatchInterceptor.class);

    @Override
    public MessageStream<?> interceptOnDispatch(
            CommandMessage command,
            ProcessingContext context,
            MessageDispatchInterceptorChain<CommandMessage> chain
    ) {

        logger.info("Dispatching command: {}", command.type());

        // Proceed with the chain
        return chain.proceed(command, context);
    }
}
// end::command-dispatch-logging[]
