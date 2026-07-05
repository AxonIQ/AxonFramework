package queries.configuration.registration;

/**
 * Supporting query used by the query handler registration samples on the configuration page.
 */
public class FetchCardSummaryQuery {

    private final String cardSummaryId;

    public FetchCardSummaryQuery(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }

    public String getCardSummaryId() {
        return cardSummaryId;
    }
}
