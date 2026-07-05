package messagingconcepts.messageintercepting.event;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

// tag::event-handler-security[]
public class EventSecurityInterceptor
        implements MessageHandlerInterceptor<EventMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(
            EventMessage event,
            ProcessingContext context,
            MessageHandlerInterceptorChain<EventMessage> chain
    ) {
        String userId = event.metadata().get("userId");
        if (userId == null || !"authorized-user".equals(userId)) {
            throw new SecurityException("Unauthorized event");
        }

        return chain.proceed(event, context);
    }
}
// end::event-handler-security[]
