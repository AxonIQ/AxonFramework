package org.axonframework.examples.sagarecipes.payment.write.cancelpayment;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


class CancelPaymentCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(
                PaymentReference.class, CancelPaymentCommandHandler.Payment.class);
        var commands = CommandHandlingModule.named("cancel-payment-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new CancelPaymentCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class Idempotency {
        @Test
        void givenPaymentConfirmed_whenCancelPayment_thenIgnored() {
            PaymentReference reference = PaymentReference.of("invoice-1");
            PaymentId paymentId = PaymentId.random();
            Amount price = Amount.euros(10);

            fixture.given().events(new PaymentPrepared(paymentId, price, reference),
                                   new PaymentConfirmed(paymentId, reference))
                   .when().command(new CancelPayment(reference, "timeout"))
                   .then().success().noEvents();
        }
    }
}
