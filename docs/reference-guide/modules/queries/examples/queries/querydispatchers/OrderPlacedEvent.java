package queries.querydispatchers;

/**
 * Supporting event used by the query-dispatch-with-processing-context sample on the query-dispatchers page.
 */
public class OrderPlacedEvent {

    private final String productId;

    public OrderPlacedEvent(String productId) {
        this.productId = productId;
    }

    public String getProductId() {
        return productId;
    }
}
