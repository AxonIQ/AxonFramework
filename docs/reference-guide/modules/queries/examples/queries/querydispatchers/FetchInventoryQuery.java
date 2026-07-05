package queries.querydispatchers;

/**
 * Supporting query used by the query-dispatch-with-processing-context sample on the query-dispatchers page.
 */
public class FetchInventoryQuery {

    private final String productId;

    public FetchInventoryQuery(String productId) {
        this.productId = productId;
    }

    public String getProductId() {
        return productId;
    }
}
