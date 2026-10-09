package migration.paths.sagastoworkflows;

import io.axoniq.framework.workflow.annotation.Workflow;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.dsl.api.StepFailedException;
import io.axoniq.framework.workflow.dsl.api.WorkflowStepResult;
import java.time.Duration;
import static migration.paths.sagastoworkflows.Messages.*;
import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo;
import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.payloadProperty;
import static io.axoniq.framework.workflow.runtime.association.Associations.associate;

// tag::parallel-workflow[]
public class ParallelChecksWorkflow {
    // tag::parallel-process[]
    @Workflow(workflowName = "ParallelChecks", workflowNamespace = "upgrade.examples",
              idProperty = "orderId", startOnEventClass = ChecksRequested.class)
    public void execute(SimpleWorkflowContext ctx, ChecksService checks) {
        String orderId = (String) ctx.workflowPayload().get("orderId");
        var order = associate(payloadProperty("orderId"), equalsTo(orderId));
        var credit = ctx.waitForEvent("credit", CreditChecked.class, order,
                step -> step.timeout(Duration.ofDays(1)));
        var stock = ctx.waitForEvent("stock", StockChecked.class, order,
                step -> step.timeout(Duration.ofDays(1)));

        // Start both requests before waiting for either one.
        var creditRequest = ctx.execute("requestCredit", (pc, input) -> {
            checks.requestCreditCheck(orderId);
            return input;
        });
        var stockRequest = ctx.execute("requestStock", (pc, input) -> {
            checks.requestStockCheck(orderId);
            return input;
        });
        if (!ctx.allMatch(WorkflowStepResult::success, creditRequest, stockRequest).success()) {
            ctx.fail(new StepFailedException("Both check requests must succeed"));
        }
        boolean accepted = ctx.allMatch(WorkflowStepResult::success, credit, stock).success()
                && credit.resultAs(CreditChecked.class).orElseThrow().accepted()
                && stock.resultAs(StockChecked.class).orElseThrow().accepted();
        if (!accepted) ctx.fail(new StepFailedException("Checks rejected or timed out"));
    }
    // end::parallel-process[]
}
// end::parallel-workflow[]
