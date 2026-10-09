package migration.paths.sagastoworkflows;

import org.axonframework.test.saga.SagaTestFixture;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static migration.paths.sagastoworkflows.Messages.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SagaComparisonTest {
    private final RecordingServices services = new RecordingServices();
    private <T> SagaTestFixture<T> fixture(Class<T> type) {
        var fixture = new SagaTestFixture<>(type);
        fixture.registerResource(org.axonframework.eventhandling.gateway.DefaultEventGateway.builder()
                .eventBus(fixture.getEventBus()).build());
        fixture.registerResource(services);
        return fixture;
    }

    @Test
    void requestsBothChecksBeforeReceivingEitherResult() {
        fixture(ParallelChecksSaga.class).givenNoPriorActivity()
                .whenPublishingA(new ChecksRequested("o1"))
                .expectDispatchedCommands().expectPublishedEvents().expectActiveSagas(1);
        assertEquals(java.util.List.of("credit:o1", "stock:o1"), services.calls);
    }

    @Test
    void joinsResultsInEitherOrderAndIgnoresDuplicates() {
        for (boolean creditFirst : new boolean[] {true, false}) {
            var fixture = fixture(ParallelChecksSaga.class);
            Object first = creditFirst ? new CreditChecked("o1", true) : new StockChecked("o1", true);
            Object last = creditFirst ? new StockChecked("o1", true) : new CreditChecked("o1", true);
            fixture.givenAPublished(new ChecksRequested("o1"))
                    .andThenAPublished(first).andThenAPublished(first)
                    .whenPublishingA(last)
                    .expectPublishedEvents(new ChecksCompleted("o1", true)).expectActiveSagas(0);
        }
    }

    @Test
    void joinsNegativeResult() {
        var fixture = fixture(ParallelChecksSaga.class);
        fixture.givenAPublished(new ChecksRequested("o1"))
                .andThenAPublished(new StockChecked("o1", false))
                .whenPublishingA(new CreditChecked("o1", true))
                .expectPublishedEvents(new ChecksCompleted("o1", false)).expectActiveSagas(0);
    }

    @Test
    void approvalCancelsDeadline() {
        var fixture = fixture(ApprovalSaga.class);
        fixture.givenAPublished(new ApprovalRequested("o1"))
                .whenPublishingA(new ApprovalReceived("o1"))
                .expectPublishedEvents(new ApprovalCompleted("o1", true))
                .expectNoScheduledDeadlines().expectActiveSagas(0);
    }

    @Test
    void missingApprovalExpires() {
        var fixture = fixture(ApprovalSaga.class);
        fixture.givenAPublished(new ApprovalRequested("o1"))
                .whenTimeElapses(Duration.ofHours(1))
                .expectPublishedEvents(new ApprovalCompleted("o1", false)).expectActiveSagas(0);
    }

    @Test
    void missingParallelResultsExpireAsRejected() {
        fixture(ParallelChecksSaga.class).givenAPublished(new ChecksRequested("o1"))
                .whenTimeElapses(Duration.ofDays(1))
                .expectPublishedEvents(new ChecksCompleted("o1", false)).expectActiveSagas(0);
    }

    @Test
    void oneMissingParallelResultExpiresAsRejected() {
        fixture(ParallelChecksSaga.class).givenAPublished(new ChecksRequested("o1"))
                .andThenAPublished(new CreditChecked("o1", true))
                .whenTimeElapses(Duration.ofDays(1))
                .expectPublishedEvents(new ChecksCompleted("o1", false)).expectActiveSagas(0);
    }

    @Test
    void serviceFailureUsesTheSameThreeAttemptLimit() throws Exception {
        var fixture = fixture(DeliverySaga.class);
        var database = new DeliveryDatabase();
        var calls = new AtomicInteger();
        fixture.registerResource(database.repository);
        fixture.registerResource((DeliveryClient) id -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("Undeliverable");
        });
        fixture.givenAPublished(new DeliveryRequested("o1"))
                .andThenTimeElapses(Duration.ofSeconds(3))
                .whenTimeElapses(Duration.ofSeconds(3))
                .expectPublishedEvents().expectNoScheduledDeadlines().expectActiveSagas(0);
        assertEquals(Boolean.FALSE, database.delivered());
        assertEquals(3, calls.get());
    }

    @Test
    void deliverySucceedsOnThirdAttemptWithWrappedTransientFailure() throws Exception {
        var calls = new AtomicInteger();
        var fixture = fixture(DeliverySaga.class);
        var database = new DeliveryDatabase();
        fixture.registerResource(database.repository);
        fixture.registerResource((DeliveryClient) id -> { if (calls.incrementAndGet() < 3) throw new IllegalStateException(new TemporaryFailure()); });
        fixture.givenAPublished(new DeliveryRequested("o1"))
                .andThenTimeElapses(Duration.ofSeconds(3))
                .whenTimeElapses(Duration.ofSeconds(3))
                .expectPublishedEvents().expectActiveSagas(0);
        assertEquals(Boolean.TRUE, database.delivered());
        assertEquals(3, calls.get());
    }

    @Test
    void deliveryStopsAfterThreeAttempts() throws Exception {
        var calls = new AtomicInteger();
        var fixture = fixture(DeliverySaga.class);
        var database = new DeliveryDatabase();
        fixture.registerResource(database.repository);
        fixture.registerResource((DeliveryClient) id -> { calls.incrementAndGet(); throw new TemporaryFailure(); });
        fixture.givenAPublished(new DeliveryRequested("o1"))
                .andThenTimeElapses(Duration.ofSeconds(3))
                .whenTimeElapses(Duration.ofSeconds(3))
                .expectPublishedEvents().expectActiveSagas(0);
        assertEquals(Boolean.FALSE, database.delivered());
        assertEquals(3, calls.get());
    }
}
