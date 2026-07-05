package messagingconcepts.messageintercepting.command;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::command-handler-logging[]
public class LoggingInterceptor
        implements MessageHandlerInterceptor<CommandMessage> {

    private static final Logger logger =
            LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public MessageStream<?> interceptOnHandle(
            CommandMessage command,
            ProcessingContext context,
            MessageHandlerInterceptorChain<CommandMessage> chain
    ) {
        logger.info("Before handling: {}", command.type());

        // Register action to run after successful handling
        context.whenComplete(ctx -> {
            logger.info("Successfully handled: {}", command.type());
        });

        // Register error handler
        context.onError((ctx, phase, error) -> {
            logger.error("Error handling {}: {}", command.type(), error.getMessage());
        });

        return chain.proceed(command, context);
    }
}
// end::command-handler-logging[]
