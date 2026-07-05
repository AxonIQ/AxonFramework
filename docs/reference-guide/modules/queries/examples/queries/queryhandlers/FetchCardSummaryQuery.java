package queries.queryhandlers;

// tag::fetch-card-summary-query[]
import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(namespace = "giftcard", name = "FetchCardSummary", version = "1.0")
public class FetchCardSummaryQuery {

    private final String cardSummaryId;

    public FetchCardSummaryQuery(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }
    // omitted getters, equals/hashCode, toString functions
    // end::fetch-card-summary-query[]

    public String getCardSummaryId() {
        return cardSummaryId;
    }
    // tag::fetch-card-summary-query[]
}
// end::fetch-card-summary-query[]
