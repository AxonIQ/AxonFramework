package messagingconcepts.componentmessageintercepting.eventbefore;

import messagingconcepts.componentmessageintercepting.CardIssuedEvent;
import messagingconcepts.componentmessageintercepting.Tracer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

// tag::event-interceptor-before[]
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

public class CardSummaryProjection {
    // end::event-interceptor-before[]

    private final Tracer tracer = new Tracer();
    // tag::event-interceptor-before[]

    @EventHandlerInterceptor
    void trace(EventMessage event) {
        tracer.startSpan(event.type().qualifiedName());
    }

    @EventHandler
    void on(CardIssuedEvent event, ProcessingContext context) {
        // Handle event
    }
}
// end::event-interceptor-before[]
