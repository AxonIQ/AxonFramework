package org.axonframework.examples.sagarecipes.saga.automations.whencancelrentalpaymentthencancelpayment;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
class WhenCancelRentalPaymentThenCancelPayment {
    @CommandHandler
    CompletableFuture<?> react(CancelRentalPayment command, @InjectEntity PaymentState state,
                               CommandDispatcher dispatcher) {
        if (state.confirmed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId()),
                                                 "not confirmed in time"), Object.class);
    }

    @EventSourced(idType = RentalId.class)
    static class PaymentState {
        private boolean confirmed;
        @ForcedEntityCreator PaymentState() { }
        @EventSourcingHandler void evolve(PaymentPrepared event) { }
        @EventSourcingHandler void evolve(PaymentConfirmed event) { confirmed = true; }
        @EventCriteriaBuilder static EventCriteria criteria(RentalId id) {
            return EventCriteria.havingTags(Tag.of(PaymentTags.PAYMENT_REFERENCE,
                                                   RentalPaymentReference.forRental(id).raw()))
                                .andBeingOneOfTypes(PaymentPrepared.class.getName(), PaymentConfirmed.class.getName());
        }
    }
}
