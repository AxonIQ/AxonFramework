package messagingconcepts.componentmessageintercepting;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

public class TracingEventInterceptor implements MessageHandlerInterceptor<EventMessage> {

    private final Tracer tracer;

    public TracingEventInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public MessageStream<?> interceptOnHandle(EventMessage event,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<EventMessage> chain) {
        tracer.startSpan(event.type().qualifiedName());
        return chain.proceed(event, context);
    }
}
