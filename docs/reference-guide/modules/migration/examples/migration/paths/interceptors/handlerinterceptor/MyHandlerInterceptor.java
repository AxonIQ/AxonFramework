package migration.paths.interceptors.handlerinterceptor;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::handler-interceptor[]
public class MyHandlerInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> chain) {
        // Pre-processing
        context.runOnAfterCommit(ctx -> {
            // Post-commit logic
        });

        // Continue chain - returns MessageStream
        return chain.proceed(message, context);
    }
}
// end::handler-interceptor[]
