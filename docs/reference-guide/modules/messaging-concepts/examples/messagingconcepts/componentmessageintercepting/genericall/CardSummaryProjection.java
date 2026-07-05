package messagingconcepts.componentmessageintercepting.genericall;

import messagingconcepts.componentmessageintercepting.CardIssuedEvent;
import messagingconcepts.componentmessageintercepting.CardSummaryView;
import messagingconcepts.componentmessageintercepting.FindCardSummaryQuery;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::message-interceptor-all[]
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class CardSummaryProjection {

    @MessageHandlerInterceptor
    void interceptAll(Message message) {
        // Runs before every @EventHandler and @QueryHandler on this component
    }

    @EventHandler
    void on(CardIssuedEvent event, ProcessingContext context) {
        // Handle event
    }

    @QueryHandler
    CardSummaryView handle(FindCardSummaryQuery query, ProcessingContext context) {
        // Handle query
        return null;
    }
}
// end::message-interceptor-all[]
