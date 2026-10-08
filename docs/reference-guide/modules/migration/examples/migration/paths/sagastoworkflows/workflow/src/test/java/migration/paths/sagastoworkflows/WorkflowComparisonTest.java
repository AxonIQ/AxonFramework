package migration.paths.sagastoworkflows;

import io.axoniq.framework.workflow.configuration.WorkflowModule;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.dsl.api.WorkflowStatus;
import io.axoniq.framework.workflow.dsl.api.StepStatus;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static migration.paths.sagastoworkflows.Messages.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowComparisonTest {
    private WorkflowTestFixture<?, ?> fixture;
    private final RecordingServices services = new RecordingServices();

    private void start(Object workflow) {
        start(workflow, services);
    }

    private void start(Object workflow, ApprovalService approvals) {
        fixture = WorkflowTestFixture.of(WorkflowModule.defaults("Comparison", SimpleWorkflowContext.class)
                .definition(definition -> definition.autodetected(configuration -> workflow)),
                configuration -> configuration.componentRegistry(registry -> registry
                        .registerComponent(ChecksService.class, c -> services)
                        .registerComponent(ApprovalService.class, c -> approvals)));
    }

    @AfterEach
    void stop() {
        if (fixture != null) fixture.then().stop();
    }

    @Test
    void joinsCreditThenStock() { parallel(true, true); }
    @Test
    void joinsStockThenCredit() { parallel(false, true); }
    @Test
    void joinsNegativeResult() { parallel(false, false); }

    private void parallel(boolean creditFirst, boolean accepted) {
        start(new ParallelChecksWorkflow());
        fixture.when().publishEvent(new ChecksRequested("o1"));
        assertThat(services.calls).isEmpty();
        fixture.when().execute("requestCredit").execute("requestStock");
        Object first = creditFirst ? new CreditChecked("o1", accepted) : new StockChecked("o1", accepted);
        Object last = creditFirst ? new StockChecked("o1", true) : new CreditChecked("o1", true);
        fixture.when().publishEvent(first).publishEvent(first);
        fixture.then().workflowNotFinished();
        fixture.when().publishEvent(last);
        fixture.then().workflowFinished(accepted ? WorkflowStatus.COMPLETED : WorkflowStatus.FAILED)
                .noStep("completed");
        assertThat(services.calls).containsExactlyInAnyOrder("credit:o1", "stock:o1");
    }

    @Test
    void serviceStepsActuallyOverlap() throws Exception {
        var bothStarted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        ChecksService checks = new ChecksService() {
            private void invoke() {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                bothStarted.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    active.decrementAndGet();
                }
            }
            public void requestCreditCheck(String orderId) { invoke(); }
            public void requestStockCheck(String orderId) { invoke(); }
        };
        var module = WorkflowModule.defaults("ParallelComparison", SimpleWorkflowContext.class)
                .definition(definition -> definition.autodetected(c -> new ParallelChecksWorkflow()));
        var driver = WorkflowTestDriver.live(module, configuration -> configuration
                .componentRegistry(registry -> registry.registerComponent(ChecksService.class, c -> checks)));
        try {
            driver.publishEvent(new ChecksRequested("parallel-1"));
            assertThat(bothStarted.await(10, TimeUnit.SECONDS)).as("Both calls start before either returns").isTrue();
            assertThat(peak.get()).isEqualTo(2);
            release.countDown();
            driver.publishEvent(new StockChecked("parallel-1", true));
            driver.publishEvent(new CreditChecked("parallel-1", true));
            driver.awaitEventually("Parallel checks join and complete", () -> {
                var state = driver.historyExists().state();
                assertThat(state.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
                assertThat(state.containsStep("completed")).isFalse();
            });
        } finally {
            release.countDown();
            driver.shutdown();
        }
    }

    @Test
    void missingParallelResultsExpireAsRejected() { expireParallelChecks(false); }

    @Test
    void oneMissingParallelResultExpiresAsRejected() { expireParallelChecks(true); }

    private void expireParallelChecks(boolean creditArrives) {
        start(new ParallelChecksWorkflow());
        fixture.when().publishEvent(new ChecksRequested("o1")).execute("requestCredit").execute("requestStock");
        if (creditArrives) fixture.when().publishEvent(new CreditChecked("o1", true));
        fixture.when().timePasses(Duration.ofDays(1));
        fixture.then().workflowFinished(WorkflowStatus.FAILED).noStep("completed");
    }

    @Test
    void approvalBeforeTimeoutCompletes() {
        start(new ApprovalWorkflow());
        fixture.when().publishEvent(new ApprovalRequested("o1"));
        assertThat(services.calls).isEmpty();
        fixture.when().execute("requestApproval").publishEvent(new ApprovalReceived("o1"));
        assertThat(services.calls).containsExactly("approval:o1");
        fixture.then().stepsPassed("completed").noStep("expired").workflowFinished(WorkflowStatus.COMPLETED);
        fixture.then().workflowStateSatisfies(state -> assertThat(state.getStep("completed").result())
                .isEqualTo(new ApprovalCompleted("o1", true)));
    }

    @Test
    void missingApprovalExpires() {
        start(new ApprovalWorkflow());
        fixture.when().publishEvent(new ApprovalRequested("o1")).execute("requestApproval")
                .timePasses(Duration.ofHours(1).plusSeconds(1));
        fixture.then().stepsPassed("expired").noStep("completed").workflowFinished(WorkflowStatus.COMPLETED);
        fixture.then().workflowStateSatisfies(state -> assertThat(state.getStep("expired").result())
                .isEqualTo(new ApprovalCompleted("o1", false)));
    }

    @Test
    void approvalServiceFailureFailsTheWorkflow() {
        start(new ApprovalWorkflow(), orderId -> { throw new IllegalStateException("Unavailable"); });
        fixture.when().publishEvent(new ApprovalRequested("o1")).execute("requestApproval");
        fixture.then().noStep("completed").noStep("expired").workflowFinished(WorkflowStatus.FAILED);
    }

    @Test
    void technicalDeliveryTimeoutDoesNotRecordAFalseBusinessOutcome() {
        var database = new DeliveryDatabase();
        var release = new CountDownLatch(1);
        var module = WorkflowModule.defaults("DeliveryTimeout", SimpleWorkflowContext.class)
                .definition(definition -> definition.autodetected(c -> new DeliveryWorkflow()));
        DeliveryClient client = id -> {
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        var driver = WorkflowTestDriver.live(module, configuration -> configuration
                .componentRegistry(registry -> registry
                        .registerComponent(DeliveryClient.class, c -> client)
                        .registerComponent(DeliveryRepository.class, c -> database.repository)));
        try {
            driver.publishEvent(new DeliveryRequested("o1"));
            driver.awaitEventually("Timeout leaves the business outcome unknown", () -> {
                assertThat(driver.historyExists().state().workflowStatus()).isEqualTo(WorkflowStatus.FAILED);
                assertThat(database.delivered()).isNull();
            });
        } finally {
            release.countDown();
            driver.shutdown();
        }
    }

    @Test
    void recordingDeliveryIsSafeToRepeat() {
        var database = new DeliveryDatabase();
        assertThat(database.delivered()).isNull();
        database.repository.record("o1", true);
        database.repository.record("o1", true);
        assertThat(database.delivered()).isTrue();
    }

    @Test
    void deliverySucceedsOnThirdAttemptWithWrappedTransientFailure() { verifyDelivery(true, false); }

    @Test
    void deliveryStopsAfterThreeAttempts() { verifyDelivery(false, false); }

    @Test
    void serviceFailureUsesTheSameThreeAttemptLimit() { verifyDelivery(false, true); }

    private void verifyDelivery(boolean succeeds, boolean permanent) {
        var database = new DeliveryDatabase();
        var calls = new AtomicInteger();
        DeliveryClient client = id -> {
            int attempt = calls.incrementAndGet();
            if (permanent) throw new IllegalArgumentException("Undeliverable");
            if (!succeeds) throw new TemporaryFailure();
            if (attempt < 3) throw new IllegalStateException(new TemporaryFailure());
        };
        var workflow = new DeliveryWorkflow();
        var module = WorkflowModule.defaults("DeliveryComparison", SimpleWorkflowContext.class)
                .definition(definition -> definition.autodetected(configuration -> workflow));
        // Use the in-memory engine with its real scheduler: the 5.4.0 stepper waits for a
        // terminal step result and cannot advance virtual time while execute() is retrying.
        var driver = WorkflowTestDriver.live(module, configuration -> configuration
                .componentRegistry(registry -> registry
                        .registerComponent(DeliveryClient.class, c -> client)
                        .registerComponent(DeliveryRepository.class, c -> database.repository)));
        try {
            driver.publishEvent(new DeliveryRequested("o1"));
            driver.awaitEventually("Delivery reaches a terminal business result", () -> {
                var state = driver.historyExists().state();
                assertThat(state.workflowStatus()).as("calls=%s, step=%s", calls.get(), state.getStep("deliver")).isEqualTo(WorkflowStatus.COMPLETED);
                assertThat(state.getStep("recordDelivery").status()).isEqualTo(StepStatus.COMPLETED);
                assertThat(database.delivered()).isEqualTo(succeeds);
            });
            assertThat(calls.get()).isEqualTo(3);
        } finally {
            driver.shutdown();
        }
    }
}
