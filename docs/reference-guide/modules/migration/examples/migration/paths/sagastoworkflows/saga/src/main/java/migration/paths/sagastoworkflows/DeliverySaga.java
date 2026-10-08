package migration.paths.sagastoworkflows;

import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.SagaLifecycle;
import static migration.paths.sagastoworkflows.Messages.*;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.annotation.DeadlineHandler;
import java.time.Duration;

// tag::retry-saga[]
public class DeliverySaga {
    private static final String RETRY = "delivery-retry";
    private int attempts;

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void on(DeliveryRequested event, DeliveryClient client,
                   DeadlineManager deadlines, DeliveryRepository deliveries) {
        attempt(event.orderId(), client, deadlines, deliveries);
    }

    @DeadlineHandler(deadlineName = RETRY)
    public void retry(String orderId, DeliveryClient client,
                      DeadlineManager deadlines, DeliveryRepository deliveries) {
        attempt(orderId, client, deadlines, deliveries);
    }

    private void attempt(String orderId, DeliveryClient client,
                         DeadlineManager deadlines, DeliveryRepository deliveries) {
        attempts++;
        boolean delivered;
        try {
            client.send(orderId);
            delivered = true;
        } catch (RuntimeException failure) {
            if (attempts < 3) {
                deadlines.schedule(Duration.ofSeconds(3), RETRY, orderId);
                return;
            }
            delivered = false;
        }
        deliveries.record(orderId, delivered);
        SagaLifecycle.end();
    }

}
// end::retry-saga[]
