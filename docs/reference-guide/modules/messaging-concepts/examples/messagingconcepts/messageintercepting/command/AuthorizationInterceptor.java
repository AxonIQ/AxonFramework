package messagingconcepts.messageintercepting.command;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::command-handler-authorization[]
public class AuthorizationInterceptor
        implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(
            CommandMessage command,
            ProcessingContext context,
            MessageHandlerInterceptorChain<CommandMessage> chain
    ) {

        String userId = command.metadata().get("userId");

        if (userId == null) {
            throw new SecurityException("No user ID in metadata");
        }

        if (!"authorized-user".equals(userId)) {
            throw new SecurityException("User not authorized");
        }

        // User is authorized, proceed
        return chain.proceed(command, context);
    }
}
// end::command-handler-authorization[]
