package migration.paths.testfixtures.setup;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Minimal interceptor used only to demonstrate reusing a production {@code ApplicationConfigurer} in tests; not
 * otherwise elaborated in this example.
 */
class MyCommandHandlingInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        return chain.proceed(message, context);
    }
}
