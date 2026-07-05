package messagingconcepts.componentmessageintercepting.commandsurround;

import messagingconcepts.componentmessageintercepting.AccessDeniedException;
import messagingconcepts.componentmessageintercepting.PlaceOrderCommand;
import messagingconcepts.componentmessageintercepting.SecurityContext;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::command-interceptor-surround[]
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;

public class OrderCommandHandler {
    // end::command-interceptor-surround[]

    private final SecurityContext securityContext = new SecurityContext();
    // tag::command-interceptor-surround[]

    @CommandHandlerInterceptor
    MessageStream<?> authorize(
            CommandMessage command,
            MessageHandlerInterceptorChain<CommandMessage> chain,
            ProcessingContext context
    ) {
        if (!securityContext.isAuthorized(command)) {
            return MessageStream.failed(new AccessDeniedException("Not authorized"));
        }
        return chain.proceed(command, context);
    }

    @CommandHandler
    void handle(PlaceOrderCommand command, ProcessingContext context) {
        // Handle the command
    }
}
// end::command-interceptor-surround[]
