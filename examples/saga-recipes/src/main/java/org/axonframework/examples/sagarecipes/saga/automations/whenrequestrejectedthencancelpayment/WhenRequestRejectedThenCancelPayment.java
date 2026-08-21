package org.axonframework.examples.sagarecipes.saga.automations.whenrequestrejectedthencancelpayment;

import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
@Namespace("rental-payment-automations")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
class WhenRequestRejectedThenCancelPayment {
    @EventHandler
    CompletableFuture<?> react(RequestRejected event, CommandDispatcher dispatcher) {
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId()),
                                                 "rental request rejected"), Object.class);
    }
}
