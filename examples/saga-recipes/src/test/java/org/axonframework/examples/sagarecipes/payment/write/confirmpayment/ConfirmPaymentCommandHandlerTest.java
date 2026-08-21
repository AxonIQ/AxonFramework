package org.axonframework.examples.sagarecipes.payment.write.confirmpayment;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfirmPaymentCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(PaymentId.class,
                                                           ConfirmPaymentCommandHandler.Payment.class);
        var commands = CommandHandlingModule.named("confirm-payment-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new ConfirmPaymentCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach void tearDown() { fixture.stop(); }

    @Nested
    class Idempotency {
        @Test
        void givenPaymentConfirmed_whenConfirmAgain_thenNoEvent() {
            PaymentId paymentId = PaymentId.random();
            PaymentReference reference = PaymentReference.of("invoice-1");
            fixture.given().events(new PaymentPrepared(paymentId, Amount.euros(10), reference),
                                   new PaymentConfirmed(paymentId, reference))
                   .when().command(new ConfirmPayment(paymentId))
                   .then().success().noEvents();
        }
    }
}
