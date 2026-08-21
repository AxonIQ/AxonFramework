package org.axonframework.examples.sagarecipes.saga.automations.whenbikerequestedthenpreparepayment;

import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
class WhenBikeRequestedThenPreparePayment {
    @EventHandler
    CompletableFuture<?> react(BikeRequested event, CommandDispatcher dispatcher) {
        return dispatcher.send(new PreparePayment(RentalPaymentReference.forRental(event.rentalId()), PRICE),
                               Object.class);
    }
}
