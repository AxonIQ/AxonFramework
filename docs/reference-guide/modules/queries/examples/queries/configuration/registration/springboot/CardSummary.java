package queries.configuration.registration.springboot;

/**
 * Supporting read model used by the Spring Boot query handler registration sample on the configuration page.
 */
public class CardSummary {

    private final String cardSummaryId;

    public CardSummary(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }

    public String getCardSummaryId() {
        return cardSummaryId;
    }
}
