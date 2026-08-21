package org.axonframework.examples.sagarecipes.saga.deadline;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PAYMENT_TIMEOUT;
import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@ActiveProfiles("test")
@AxonSpringBootTest(properties = {"saga.recipe=repository", "saga.deadline.test-context=true"})
class PaymentsAwaitingConfirmationTest {
    @Autowired AxonTestFixture fixture;
    @Autowired PendingPaymentRepository pending;
    @Autowired PaymentsAwaitingConfirmation projection;

    @Test
    void givenPaymentPrepared_whenProjected_thenPendingRowUsesEventTimestamp() {
        PaymentReference reference = PaymentReference.of("prepared-payment");
        Instant preparedAt = Instant.parse("2026-01-01T10:00:00Z");

        fixture.given().event(at(preparedAt, new PaymentPrepared(PaymentId.random(), PRICE, reference)))
               .then().await(result -> result.expect(ignored -> {
                   PendingPayment payment = pending.findById(reference.raw()).orElseThrow();
                   assertThat(payment.preparedAt()).isEqualTo(preparedAt);
               }));
    }

    @Test
    void givenOverduePayment_whenSwept_thenCancelRentalPaymentDispatched() {
        PaymentReference reference = PaymentReference.of("overdue-rental");
        Instant preparedAt = Instant.parse("2026-01-01T10:00:00Z");

        fixture.given().event(at(preparedAt, new PaymentPrepared(PaymentId.random(), PRICE, reference)))
               .then().await(result -> result.expect(ignored ->
                       assertThat(pending.findById(reference.raw())).isPresent()))
               .expect(ignored -> projection.cancelOverduePayments(
                       preparedAt.plus(PAYMENT_TIMEOUT).plusSeconds(1)))
               .await(result -> result.commandsSatisfy(commands ->
                       assertThat(commands.stream().map(command -> command.payload()))
                               .contains(new CancelRentalPayment(RentalPaymentReference.toRental(reference)))));
    }

    @Nested
    class SettledPayments {
        @Test
        void givenPaymentConfirmed_whenProjected_thenPendingRowRemoved() {
            assertSettlementRemovesRow(reference -> new PaymentConfirmed(PaymentId.random(), reference));
        }

        @Test
        void givenPaymentRejected_whenProjected_thenPendingRowRemoved() {
            assertSettlementRemovesRow(reference -> new PaymentRejected(PaymentId.random(), reference, "declined"));
        }

        @Test
        void givenPaymentCancelled_whenProjected_thenPendingRowRemoved() {
            assertSettlementRemovesRow(reference -> new PaymentCancelled(PaymentId.random(), reference));
        }
    }

    private void assertSettlementRemovesRow(java.util.function.Function<PaymentReference, Object> settlement) {
        PaymentReference reference = PaymentReference.of(UUID.randomUUID().toString());
        Instant preparedAt = Instant.parse("2026-01-01T10:00:00Z");

        fixture.given().event(at(preparedAt, new PaymentPrepared(PaymentId.random(), PRICE, reference)))
               .then().await(result -> result.expect(ignored ->
                       assertThat(pending.findById(reference.raw())).isPresent()))
               .and().given().event(settlement.apply(reference))
               .then().await(result -> result.expect(ignored ->
                       assertThat(pending.findById(reference.raw())).isEmpty()));
    }

    private static GenericEventMessage at(Instant timestamp, Object event) {
        return new GenericEventMessage(UUID.randomUUID().toString(), new MessageType(event.getClass()), event,
                                       Map.of(), timestamp);
    }
}
