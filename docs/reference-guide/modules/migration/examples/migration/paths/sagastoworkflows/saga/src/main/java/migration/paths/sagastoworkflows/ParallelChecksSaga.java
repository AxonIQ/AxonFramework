package migration.paths.sagastoworkflows;

import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.eventhandling.gateway.EventGateway;
import static migration.paths.sagastoworkflows.Messages.*;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.annotation.DeadlineHandler;
import java.time.Duration;

// tag::parallel-saga[]
public class ParallelChecksSaga {
    private String creditDeadline;
    private String stockDeadline;
    private boolean creditSeen;
    private boolean stockSeen;
    private boolean creditAccepted;
    private boolean stockAccepted;

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void on(ChecksRequested event, ChecksService checks, DeadlineManager deadlines) {
        creditDeadline = deadlines.schedule(Duration.ofDays(1), "credit-expiry", event.orderId());
        stockDeadline = deadlines.schedule(Duration.ofDays(1), "stock-expiry", event.orderId());
        // Request both operations without waiting for either result.
        checks.requestCreditCheck(event.orderId());
        checks.requestStockCheck(event.orderId());
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(CreditChecked event, EventGateway events, DeadlineManager deadlines) {
        if (creditSeen) return;
        deadlines.cancelSchedule("credit-expiry", creditDeadline);
        creditSeen = true;
        creditAccepted = event.accepted();
        finishIfReady(event.orderId(), events);
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(StockChecked event, EventGateway events, DeadlineManager deadlines) {
        if (stockSeen) return;
        deadlines.cancelSchedule("stock-expiry", stockDeadline);
        stockSeen = true;
        stockAccepted = event.accepted();
        finishIfReady(event.orderId(), events);
    }

    @DeadlineHandler(deadlineName = "credit-expiry")
    public void creditExpired(String orderId, EventGateway events) {
        if (creditSeen) return;
        creditSeen = true;
        finishIfReady(orderId, events);
    }

    @DeadlineHandler(deadlineName = "stock-expiry")
    public void stockExpired(String orderId, EventGateway events) {
        if (stockSeen) return;
        stockSeen = true;
        finishIfReady(orderId, events);
    }

    private void finishIfReady(String orderId, EventGateway events) {
        if (creditSeen && stockSeen) {
            events.publish(new ChecksCompleted(orderId, creditAccepted && stockAccepted));
            SagaLifecycle.end();
        }
    }
}
// end::parallel-saga[]
