package queries.querydispatchers;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

class InventoryEventHandler {

    // tag::query-dispatch-with-processing-context[]
    @EventHandler
    public void on(OrderPlacedEvent event,
                   ProcessingContext context,
                   QueryGateway queryGateway) {
        // Dispatch query with ProcessingContext for correlation
        queryGateway.query(
            new FetchInventoryQuery(event.getProductId()),
            Inventory.class,
            context
        ).thenAccept(inventory -> {
            // Handle inventory result...
        });
    }
    // end::query-dispatch-with-processing-context[]
}
