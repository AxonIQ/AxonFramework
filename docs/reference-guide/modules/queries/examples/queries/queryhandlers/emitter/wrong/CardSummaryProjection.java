package queries.queryhandlers.emitter.wrong;

import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.springframework.stereotype.Component;

// tag::emitter-field-injection-wrong[]
// WRONG - Do not inject as a field
@Component
public class CardSummaryProjection {
    private final QueryUpdateEmitter emitter; // WRONG!

    public CardSummaryProjection(QueryUpdateEmitter emitter) {
        this.emitter = emitter;
    }
}

// end::emitter-field-injection-wrong[]
