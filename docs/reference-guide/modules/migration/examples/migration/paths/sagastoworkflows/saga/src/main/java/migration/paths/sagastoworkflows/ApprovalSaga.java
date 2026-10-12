package migration.paths.sagastoworkflows;

import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.eventhandling.gateway.EventGateway;
import static migration.paths.sagastoworkflows.Messages.*;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.annotation.DeadlineHandler;
import java.time.Duration;

// tag::timeout-saga[]
public class ApprovalSaga {
    private static final String EXPIRY = "approval-expiry";
    private String deadlineId;

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void on(ApprovalRequested event, ApprovalService approvals, DeadlineManager deadlines) {
        deadlineId = deadlines.schedule(Duration.ofHours(1), EXPIRY, event.orderId());
        approvals.requestApproval(event.orderId());
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(ApprovalReceived event, DeadlineManager deadlines, EventGateway events) {
        deadlines.cancelSchedule(EXPIRY, deadlineId);
        events.publish(new ApprovalCompleted(event.orderId(), true));
        SagaLifecycle.end();
    }

    @DeadlineHandler(deadlineName = EXPIRY)
    public void expired(String orderId, EventGateway events) {
        events.publish(new ApprovalCompleted(orderId, false));
        SagaLifecycle.end();
    }
}
// end::timeout-saga[]
