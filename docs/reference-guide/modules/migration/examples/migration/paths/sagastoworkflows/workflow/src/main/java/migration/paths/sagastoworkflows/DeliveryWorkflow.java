package migration.paths.sagastoworkflows;

import io.axoniq.framework.workflow.annotation.Workflow;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.dsl.api.StepFailedException;
import io.axoniq.framework.workflow.dsl.api.StepTimedOutException;
import io.axoniq.framework.workflow.dsl.api.retry.RetryPolicy;
import io.axoniq.framework.workflow.dsl.api.retry.BackoffStrategy;
import java.time.Duration;
import static migration.paths.sagastoworkflows.Messages.*;

// tag::retry-workflow[]
public class DeliveryWorkflow {
    // tag::retry-process[]
    @Workflow(workflowName = "Delivery", workflowNamespace = "upgrade.examples",
              idProperty = "orderId", startOnEventClass = DeliveryRequested.class)
    public void execute(SimpleWorkflowContext ctx, DeliveryClient client, DeliveryRepository deliveries) {
        String orderId = (String) ctx.workflowPayload().get("orderId");
        var delivery = ctx.execute("deliver", (pc, input) -> {
            client.send(orderId);
            return input;
        }, step -> step.retryPolicy(RetryPolicy.maxRetries(2)
                .withBackoff(BackoffStrategy.fixed(Duration.ofSeconds(3)))
                .retryWhile(retry -> !(retry.error() instanceof StepTimedOutException))));
        if (delivery.timeout() || delivery.canceled()) {
            throw new StepFailedException("Delivery outcome unknown; reconcile before recording it");
        }

        boolean delivered = delivery.success();
        ctx.awaitExecute("recordDelivery", ctx.workflowPayload(), (pc, input) -> {
            deliveries.record(orderId, delivered);
            return input;
        });
    }
    // end::retry-process[]
}
// end::retry-workflow[]
