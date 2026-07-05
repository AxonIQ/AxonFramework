package queries.configuration.querygateway.springboot;

/**
 * Supporting query used by the QueryGateway configuration sample on the configuration page.
 */
public class FetchOrderDetailsQuery {

    private final String orderId;

    public FetchOrderDetailsQuery(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
