package queries.configuration.querygateway.springboot;

/**
 * Supporting query response used by the QueryGateway configuration sample on the configuration page.
 */
public class OrderDetails {

    private final String orderId;

    public OrderDetails(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
