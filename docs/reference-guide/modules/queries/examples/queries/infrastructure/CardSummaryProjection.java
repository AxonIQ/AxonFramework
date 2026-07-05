package queries.infrastructure;

// tag::emitting-updates[]
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.springframework.stereotype.Component;

@Component
public class CardSummaryProjection {

    @EventHandler
    public void on(CardRedeemedEvent event, ProcessingContext context) {
        // Create a context-aware emitter
        QueryUpdateEmitter emitter = QueryUpdateEmitter.forContext(context);

        // Update the model
        CardSummary summary = new CardSummary(event.cardId(), event.amount());

        // Emit update to subscription queries
        emitter.emit(
            FetchCardSummaryQuery.class,
            query -> query.cardSummaryId().equals(event.cardId()),
            summary
        );
    }
}
// end::emitting-updates[]
