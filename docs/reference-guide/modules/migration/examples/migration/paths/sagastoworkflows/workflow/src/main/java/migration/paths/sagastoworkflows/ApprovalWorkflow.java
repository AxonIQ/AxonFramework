package migration.paths.sagastoworkflows;

import io.axoniq.framework.workflow.annotation.Workflow;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.dsl.api.StepFailedException;
import java.time.Duration;
import static migration.paths.sagastoworkflows.Messages.*;
import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo;
import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.payloadProperty;
import static io.axoniq.framework.workflow.runtime.association.Associations.associate;

// tag::timeout-workflow[]
public class ApprovalWorkflow {
    // tag::timeout-process[]
    @Workflow(workflowName = "Approval", workflowNamespace = "upgrade.examples",
              idProperty = "orderId", startOnEventClass = ApprovalRequested.class)
    public void execute(SimpleWorkflowContext ctx, ApprovalService approvals) {
        String orderId = (String) ctx.workflowPayload().get("orderId");
        var approval = ctx.waitForEvent("approval", ApprovalReceived.class,
                associate(payloadProperty("orderId"), equalsTo(orderId)), step -> step.timeout(Duration.ofHours(1)));
        ctx.awaitExecute("requestApproval", ctx.workflowPayload(), (pc, input) -> {
            approvals.requestApproval(orderId);
            return input;
        });

        if (approval.success()) {
            ctx.awaitPublish("completed", new ApprovalCompleted(orderId, true));
        } else if (approval.timeout()) {
            ctx.awaitPublish("expired", new ApprovalCompleted(orderId, false));
        } else {
            ctx.fail(approval.error().orElseGet(() -> new StepFailedException("Approval wait failed")));
        }
    }
    // end::timeout-process[]
}
// end::timeout-workflow[]
