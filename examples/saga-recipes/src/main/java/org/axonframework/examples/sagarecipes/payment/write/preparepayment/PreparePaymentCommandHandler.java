package org.axonframework.examples.sagarecipes.payment.write.preparepayment;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import static org.axonframework.examples.sagarecipes.payment.PaymentId.random;

@Component
class PreparePaymentCommandHandler {
    @CommandHandler
    void handle(PreparePayment command, @InjectEntity Payment payment, EventAppender appender) {
        if (!payment.exists) {
            appender.append(new PaymentPrepared(random(), command.amount(), command.paymentReference()));
        }
    }

    @EventSourced(tagKey = PaymentTags.PAYMENT_REFERENCE, idType = PaymentReference.class)
    static class Payment {
        private boolean exists;

        @ForcedEntityCreator Payment() { }
        @EventSourcingHandler void evolve(PaymentPrepared event) { exists = true; }
    }
}
