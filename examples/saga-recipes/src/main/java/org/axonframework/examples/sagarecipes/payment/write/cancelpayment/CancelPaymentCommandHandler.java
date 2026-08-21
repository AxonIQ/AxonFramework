package org.axonframework.examples.sagarecipes.payment.write.cancelpayment;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
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
class CancelPaymentCommandHandler {
    @CommandHandler
    void handle(CancelPayment command, @InjectEntity Payment payment, EventAppender appender) {
        if (payment.status == Status.PREPARED) {
            appender.append(new PaymentCancelled(payment.paymentId, command.paymentReference()));
        }
    }

    @EventSourced(tagKey = PaymentTags.PAYMENT_REFERENCE, idType = PaymentReference.class)
    static class Payment {
        private PaymentId paymentId;
        private Status status;
        @EntityCreator Payment() { }
        @EventSourcingHandler void evolve(PaymentPrepared event) {
            paymentId = event.paymentId();
            status = Status.PREPARED;
        }
        @EventSourcingHandler void evolve(PaymentConfirmed event) { status = Status.SETTLED; }
        @EventSourcingHandler void evolve(PaymentRejected event) { status = Status.SETTLED; }
        @EventSourcingHandler void evolve(PaymentCancelled event) { status = Status.SETTLED; }
    }
    enum Status { PREPARED, SETTLED }
}
