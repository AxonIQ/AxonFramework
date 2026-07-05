package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

// tag::spring-boot-query-service[]
@Service
public class ItemService {

    private final QueryGateway queryGateway;

    public ItemService(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    public CompletableFuture<ItemDTO> findItem(String itemId) {
        return queryGateway.query(
            new FetchItemQuery(itemId),
            ItemDTO.class
        );
    }
}
// end::spring-boot-query-service[]
