package queries.configuration.querygateway.springboot;

import org.springframework.stereotype.Service;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import java.util.concurrent.CompletableFuture;

// tag::query-gateway-springboot[]
@Service
public class OrderService {

    private final QueryGateway queryGateway;

    public OrderService(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    public CompletableFuture<OrderDetails> fetchOrderDetails(String orderId) {
        return queryGateway.query(new FetchOrderDetailsQuery(orderId),
                                 OrderDetails.class);
    }
}
// end::query-gateway-springboot[]
