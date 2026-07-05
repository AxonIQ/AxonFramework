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
