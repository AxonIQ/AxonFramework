package migration.paths.interceptors.annotationbased;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::annotation-based-interceptors[]
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;

public class OrderCommandHandler {
    // end::annotation-based-interceptors[]

    private static final Logger logger = LoggerFactory.getLogger(OrderCommandHandler.class);

    private boolean isAuthorized(CommandMessage command) {
        return true;
    }
    // tag::annotation-based-interceptors[]

    // Surround-interceptor: full control over the chain
    @CommandHandlerInterceptor
    MessageStream<?> authorize(
            CommandMessage command,
            MessageHandlerInterceptorChain<CommandMessage> chain,
            ProcessingContext context
    ) {
        if (!isAuthorized(command)) {
            return MessageStream.failed(new AccessDeniedException("Not authorized"));
        }
        return chain.proceed(command, context);
    }

    // Before-interceptor: runs before the handler, chain proceeds automatically
    @CommandHandlerInterceptor
    void log(CommandMessage command) {
        logger.info("Handling command: {}", command.type().qualifiedName());
    }

    @CommandHandler
    void handle(PlaceOrderCommand command, ProcessingContext context) {
        // Handle the command
    }
}
// end::annotation-based-interceptors[]
