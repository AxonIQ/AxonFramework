package messagingconcepts.componentmessageintercepting;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

public class TenantFilterEventInterceptor implements MessageHandlerInterceptor<EventMessage> {

    private final String tenantId;

    public TenantFilterEventInterceptor(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public MessageStream<?> interceptOnHandle(EventMessage event,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<EventMessage> chain) {
        if (!tenantId.equals(event.metadata().get("tenantId"))) {
            return MessageStream.empty();
        }
        return chain.proceed(event, context);
    }
}
