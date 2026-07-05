package queries.configuration.registration;

/**
 * Supporting read model used by the query handler registration samples on the configuration page.
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
