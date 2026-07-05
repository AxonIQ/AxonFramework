package queries.querydispatchers;

/**
 * Supporting query used by the single-result-query samples on the query-dispatchers page.
 */
public class FetchItemQuery {

    private final String itemId;

    public FetchItemQuery(String itemId) {
        this.itemId = itemId;
    }

    public String getItemId() {
        return itemId;
    }
}
