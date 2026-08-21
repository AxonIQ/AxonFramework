package org.axonframework.examples.sagarecipes.payment.write.rejectpayment;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RejectPaymentCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(PaymentId.class,
                                                           RejectPaymentCommandHandler.Payment.class);
        var commands = CommandHandlingModule.named("reject-payment-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new RejectPaymentCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach void tearDown() { fixture.stop(); }

    @Nested
    class Idempotency {
        @Test
        void givenPaymentRejected_whenRejectAgain_thenNoEvent() {
            PaymentId paymentId = PaymentId.random();
            PaymentReference reference = PaymentReference.of("invoice-1");
            fixture.given().events(new PaymentPrepared(paymentId, Amount.euros(10), reference),
                                   new PaymentRejected(paymentId, reference, "declined"))
                   .when().command(new RejectPayment(paymentId, "declined"))
                   .then().success().noEvents();
        }
    }
}
