package queries.queryhandlers.parameters;

class FetchCardSummaryQuery {

    private final String cardSummaryId;

    FetchCardSummaryQuery(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }

    String getCardSummaryId() {
        return cardSummaryId;
    }
}
