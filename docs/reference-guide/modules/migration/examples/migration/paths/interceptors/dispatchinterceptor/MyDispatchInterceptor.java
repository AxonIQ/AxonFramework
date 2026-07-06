package migration.paths.interceptors.dispatchinterceptor;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;

// tag::dispatch-interceptor[]
public class MyDispatchInterceptor implements MessageDispatchInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnDispatch(CommandMessage message,
                                                @Nullable ProcessingContext context,
                                                MessageDispatchInterceptorChain<CommandMessage> chain) {
        // Modify or enrich message
        CommandMessage enrichedMessage = message.andMetadata(
            Collections.singletonMap("dispatchTime", Instant.now().toString())
        );

        // Continue chain with modified message
        return chain.proceed(enrichedMessage, context);
    }
}
// end::dispatch-interceptor[]
