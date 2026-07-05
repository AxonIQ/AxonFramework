package queries.queryhandlers.emitter.correct;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.springframework.stereotype.Component;

// tag::emitter-parameter-injection-correct[]
// CORRECT - Create from ProcessingContext
@Component
public class CardSummaryProjection {
    @EventHandler
    public void on(CardRedeemedEvent event, QueryUpdateEmitter emitter) {
        // CORRECT!
        // Use emitter...
    }
}
// end::emitter-parameter-injection-correct[]

class CardRedeemedEvent {
}
