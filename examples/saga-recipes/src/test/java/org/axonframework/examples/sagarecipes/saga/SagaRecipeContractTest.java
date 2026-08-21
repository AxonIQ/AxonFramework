package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.deadline.PaymentsAwaitingConfirmation;
import org.axonframework.examples.sagarecipes.saga.deadline.PendingPaymentRepository;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.RecordingCommandBus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@ActiveProfiles("test")
@Import(PreparePaymentFailureTestConfiguration.class)
abstract class SagaRecipeContractTest {
    @Autowired AxonTestFixture fixture;
    @Autowired PendingPaymentRepository pendingPayments;
    @Autowired PaymentsAwaitingConfirmation deadlineProjection;
    @Autowired PreparePaymentFailureTestConfiguration.PreparePaymentFailureSwitch preparePaymentFailure;

    @BeforeEach
    void retryWhileTheFixtureCommandRecorderIsBeingUpdated() {
        Awaitility.ignoreExceptionsByDefaultMatching(error ->
                error instanceof ArrayIndexOutOfBoundsException
                        && Arrays.stream(error.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(RecordingCommandBus.class.getName())));
    }

    @AfterEach
    void restoreAwaitilityDefaults() {
        Awaitility.reset();
    }

    @Test
    void givenBikeRequested_thenPaymentIsPrepared() {
        RentalId rentalId = RentalId.random();
        BikeRequested requested = new BikeRequested(BikeId.random(), "renter", rentalId);

        fixture.given().events(requested).then().await(result -> result.commandsSatisfy(commands ->
                assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                      new PreparePayment(RentalPaymentReference.forRental(rentalId), PRICE))));
    }

    @Test
    void givenPaymentConfirmed_thenRequestApproved() {
        RentalId rentalId = RentalId.random();
        BikeId bikeId = BikeId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        fixture.given().event(new BikeRequested(bikeId, "renter", rentalId))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .and().given().event(new PaymentConfirmed(PaymentId.random(), reference))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new ApproveRequest(bikeId, "renter"))));
    }

    @Test
    void givenPaymentRejected_thenRequestRejected() {
        RentalId rentalId = RentalId.random();
        BikeId bikeId = BikeId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        fixture.given().event(new BikeRequested(bikeId, "renter", rentalId))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .and().given().event(new PaymentRejected(PaymentId.random(), reference, "declined"))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new RejectRequest(bikeId, "renter"))));
    }

    @Test
    void givenRequestRejected_thenPaymentCancelled() {
        RentalId rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        fixture.given().event(new BikeRequested(BikeId.random(), "renter", rentalId))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .and().given().event(new RequestRejected(BikeId.random(), rentalId, "renter"))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertThat(commands.stream().map(command -> command.payload()))
                               .anyMatch(CancelPayment.class::isInstance)));
    }

    @Test
    void givenPaymentNotConfirmed_whenCancelRentalPayment_thenPaymentCancelled() {
        RentalId rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        fixture.given().event(new BikeRequested(BikeId.random(), "renter", rentalId))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .and().given().when().command(new CancelRentalPayment(rentalId))
               .then().success().commandsSatisfy(commands ->
                       assertThat(commands.stream().map(command -> command.payload()))
                               .anyMatch(CancelPayment.class::isInstance));
    }

    @Test
    void givenPaymentNotConfirmed_whenTimeoutElapses_thenCancellationRequested() {
        RentalId rentalId = RentalId.random();
        BikeId bikeId = BikeId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        fixture.given().event(new BikeRequested(bikeId, "renter", rentalId))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .await(result -> result.expect(ignored ->
                       assertThat(pendingPayments.findById(reference.raw())).isPresent()))
               .expect(ignored -> deadlineProjection.cancelOverduePayments(
                       Instant.parse("9999-12-31T23:59:59Z")))
               .await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new CancelRentalPayment(rentalId))));
    }

    @Test
    void givenPaymentConfirmed_whenCancelRentalPaymentArrivesLate_thenNoPaymentCancelled() {
        RentalId rentalId = RentalId.random();
        BikeId bikeId = BikeId.random();
        PaymentId paymentId = PaymentId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        fixture.given().event(new BikeRequested(bikeId, "renter", rentalId))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .and().given().event(new PaymentConfirmed(paymentId, reference))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new ApproveRequest(bikeId, "renter"))))
               .and().given().when().command(new CancelRentalPayment(rentalId))
               .then().success().noEvents();
    }

    @Test
    void givenCommandSucceededButBikeRequestedRedelivered_thenNoSecondPaymentPrepared() {
        RentalId rentalId = RentalId.random();
        BikeId bikeId = BikeId.random();
        PaymentId paymentId = PaymentId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        BikeRequested requested = new BikeRequested(bikeId, "renter", rentalId);

        fixture.given().event(requested)
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new PreparePayment(reference, PRICE))))
               .and().given().events(requested, new PaymentConfirmed(paymentId, reference))
               .then().await(result -> result.commandsSatisfy(commands ->
                       assertPayloadsContain(commands.stream().map(command -> command.payload()).toList(),
                                             new ApproveRequest(bikeId, "renter"))))
               .eventsSatisfy(events -> assertThat(events.stream()
                       .map(event -> event.payload())
                       .filter(PaymentPrepared.class::isInstance)
                       .map(PaymentPrepared.class::cast)
                       .filter(prepared -> prepared.paymentReference().equals(reference)))
                       .hasSize(1));
    }

    @Test
    void givenPreparePaymentFails_thenNoProgressIsRecorded() {
        RentalId rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        BikeRequested requested = new BikeRequested(BikeId.random(), "renter", rentalId);

        preparePaymentFailure.enable();
        var then = fixture.given().event(requested).then();
        try {
            then.await(result -> result.expect(ignored ->
                    assertThat(preparePaymentFailure.failureObserved()).isTrue()));
            then.eventsSatisfy(events -> {
                List<Object> payloads = events.stream().map(event -> event.payload()).toList();
                assertThat(payloads.stream()
                                   .filter(PaymentPrepared.class::isInstance)
                                   .map(PaymentPrepared.class::cast)
                                   .filter(prepared -> prepared.paymentReference().equals(reference)))
                        .isEmpty();
                assertNoProgressRecorded(rentalId, payloads);
            });
        } finally {
            preparePaymentFailure.disable();
        }

        then.and().given().event(requested)
            .then().await(result -> result.eventsSatisfy(events ->
                    assertThat(events.stream().map(event -> event.payload()))
                            .anyMatch(event -> event instanceof PaymentPrepared prepared
                                    && prepared.paymentReference().equals(reference))),
                          java.time.Duration.ofSeconds(15));
    }

    protected void assertNoProgressRecorded(RentalId rentalId, List<Object> recordedPayloads) {
        // Recipes without process-owned state have nothing additional to assert.
    }

    private static void assertPayloadsContain(List<Object> payloads, Object expected) {
        assertThat(payloads).contains(expected);
    }

}
