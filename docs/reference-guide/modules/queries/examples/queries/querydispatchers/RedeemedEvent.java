package queries.querydispatchers;

/**
 * Supporting event used by the emitting-updates sample on the query-dispatchers page.
 */
public class RedeemedEvent {

    private final String id;
    private final int amount;

    public RedeemedEvent(String id, int amount) {
        this.id = id;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public int getAmount() {
        return amount;
    }
}
