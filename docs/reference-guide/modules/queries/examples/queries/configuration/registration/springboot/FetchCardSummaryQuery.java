package queries.configuration.registration.springboot;

/**
 * Supporting query used by the Spring Boot query handler registration sample on the configuration page.
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
