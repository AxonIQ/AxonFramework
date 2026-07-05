package queries.queryhandlers.matching;

// tag::fetch-card-summary-query-with-getter[]
import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(namespace = "giftcard", name = "FetchCardSummary", version = "1.0")
public class FetchCardSummaryQuery {
    private final String cardSummaryId;

    public FetchCardSummaryQuery(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }

    public String getCardSummaryId() {
        return cardSummaryId;
    }
}
// end::fetch-card-summary-query-with-getter[]
