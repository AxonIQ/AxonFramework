package messagingconcepts.messageintercepting.event;

import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::event-dispatch-logging[]
public class EventLoggingDispatchInterceptor
        implements MessageDispatchInterceptor<EventMessage> {

    private static final Logger logger =
            LoggerFactory.getLogger(EventLoggingDispatchInterceptor.class);

    @Override
    public MessageStream<?> interceptOnDispatch(
            EventMessage event,
            ProcessingContext context,
            MessageDispatchInterceptorChain<EventMessage> chain
    ) {

        logger.info("Publishing event: {}", event.type());
        return chain.proceed(event, context);
    }
}
// end::event-dispatch-logging[]
