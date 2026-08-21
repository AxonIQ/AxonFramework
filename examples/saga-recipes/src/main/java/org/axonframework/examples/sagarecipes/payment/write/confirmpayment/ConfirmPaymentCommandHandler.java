package org.axonframework.examples.sagarecipes.payment.write.confirmpayment;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

@Component
class ConfirmPaymentCommandHandler {
    @CommandHandler
    void handle(ConfirmPayment command, @InjectEntity Payment payment, EventAppender appender) {
        if (payment.status == Status.PREPARED) {
            appender.append(new PaymentConfirmed(command.paymentId(), payment.reference));
        }
    }

    @EventSourced(tagKey = PaymentTags.PAYMENT_ID, idType = PaymentId.class)
    static class Payment {
        private PaymentReference reference;
        private Status status = Status.NONE;
        @ForcedEntityCreator Payment() { }
        @EventSourcingHandler void evolve(PaymentPrepared event) {
            reference = event.paymentReference();
            status = Status.PREPARED;
        }
        @EventSourcingHandler void evolve(PaymentConfirmed event) { status = Status.SETTLED; }
        @EventSourcingHandler void evolve(PaymentRejected event) { status = Status.SETTLED; }
        @EventSourcingHandler void evolve(PaymentCancelled event) { status = Status.SETTLED; }
    }

    enum Status { NONE, PREPARED, SETTLED }
}
