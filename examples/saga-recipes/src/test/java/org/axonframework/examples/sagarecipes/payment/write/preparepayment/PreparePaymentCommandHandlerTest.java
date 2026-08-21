package org.axonframework.examples.sagarecipes.payment.write.preparepayment;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.axonframework.examples.sagarecipes.payment.PaymentId.random;

class PreparePaymentCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(
                PaymentReference.class, PreparePaymentCommandHandler.Payment.class);
        var commands = CommandHandlingModule.named("prepare-payment-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new PreparePaymentCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void givenNoPayment_whenPreparePayment_thenPaymentPrepared() {
        PaymentReference reference = PaymentReference.of("invoice-1");
        Amount price = Amount.euros(10);

        fixture.given().noPriorActivity()
               .when().command(new PreparePayment(reference, price))
               .then().success().eventsSatisfy(events -> {
                   PaymentPrepared prepared = (PaymentPrepared) events.getFirst().payload();
                   org.assertj.core.api.Assertions.assertThat(prepared.amount()).isEqualTo(price);
                   org.assertj.core.api.Assertions.assertThat(prepared.paymentReference()).isEqualTo(reference);
               });
    }

    @Nested
    class Idempotency {
        @Test
        void givenPaymentPrepared_whenPreparePaymentAgain_thenNoEvent() {
            PaymentReference reference = PaymentReference.of("invoice-1");
            Amount price = Amount.euros(10);

            fixture.given().events(new PaymentPrepared(random(), price, reference))
                   .when().command(new PreparePayment(reference, price))
                   .then().success().noEvents();
        }
    }
}
