package queries.querydispatchers;

/**
 * Supporting read model used by the subscription-query and streaming-query samples on the query-dispatchers page.
 */
public class CardSummary {

    private int remainingValue;

    public int getRemainingValue() {
        return remainingValue;
    }

    public void setRemainingValue(int remainingValue) {
        this.remainingValue = remainingValue;
    }
}
