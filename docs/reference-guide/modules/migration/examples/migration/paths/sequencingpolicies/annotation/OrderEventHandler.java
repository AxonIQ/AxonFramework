package migration.paths.sequencingpolicies.annotation;

// tag::annotation-sequencing-policy[]
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;

@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"customerId"})
class OrderEventHandler {

    @EventHandler
    public void on(OrderPlacedEvent event) {
        // handled sequentially per customerId
    }
}
// end::annotation-sequencing-policy[]

record OrderPlacedEvent(String orderId, String customerId) {

}
