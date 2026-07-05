package messagingconcepts.componentmessageintercepting.eventsurround;

import messagingconcepts.componentmessageintercepting.CardIssuedEvent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::event-interceptor-surround[]
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;

public class CardSummaryProjection {
    // end::event-interceptor-surround[]

    private final String tenantId = "tenant-1";
    // tag::event-interceptor-surround[]

    @EventHandlerInterceptor
    MessageStream<?> filterByTenant(
            EventMessage event,
            MessageHandlerInterceptorChain<EventMessage> chain,
            ProcessingContext context
    ) {
        if (!tenantId.equals(event.metadata().get("tenantId"))) {
            return MessageStream.empty();
        }
        return chain.proceed(event, context);
    }

    @EventHandler
    void on(CardIssuedEvent event, ProcessingContext context) {
        // Handle event
    }
}
// end::event-interceptor-surround[]
