package queries.querydispatchers;

import jakarta.persistence.EntityManager;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import java.util.List;

class CardSummaryProjection {

    private EntityManager entityManager;

    // tag::subscription-query-handler[]
    @QueryHandler
    public List<CardSummary> handle(FetchCardSummariesQuery query) {
        return entityManager
            .createNamedQuery("CardSummary.fetch", CardSummary.class)
            .setParameter("idStartsWith", query.getFilter().getIdStartsWith())
            .setFirstResult(query.getOffset())
            .setMaxResults(query.getLimit())
            .getResultList();
    }
    // end::subscription-query-handler[]

    // tag::emitting-updates[]
    @EventHandler
    public void on(RedeemedEvent event, QueryUpdateEmitter emitter) {
        // <1>
        CardSummary summary = entityManager.find(CardSummary.class, event.getId());
        summary.setRemainingValue(summary.getRemainingValue() - event.getAmount());

        // <2>
        emitter.emit(
            FetchCardSummariesQuery.class, // <3>
            query -> event.getId().startsWith(query.getFilter().getIdStartsWith()), // <4>
            summary // <5>
        );
    }
    // end::emitting-updates[]
}
