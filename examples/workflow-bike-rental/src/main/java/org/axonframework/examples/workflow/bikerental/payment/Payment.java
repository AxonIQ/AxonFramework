/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */
package org.axonframework.examples.workflow.bikerental.payment;

import org.axonframework.examples.workflow.bikerental.coreapi.payment.ConfirmPaymentCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentConfirmedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentPreparedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentRejectedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.RejectPaymentCommand;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

/**
 * @since 5.4.0
 */
@EventSourced(tagKey = "Payment")
public class Payment {

    private boolean closed;
    private String paymentReference;

    @EntityCreator
    public Payment() {
    }

    @CommandHandler
    public void handle(ConfirmPaymentCommand command, EventAppender eventAppender) {
        if (paymentReference == null) {
            throw new IllegalStateException("Payment not prepared yet");
        }
        if (!closed) {
            eventAppender.append(new PaymentConfirmedEvent(command.paymentId(), paymentReference));
        }
    }

    @CommandHandler
    public void handle(RejectPaymentCommand command, EventAppender eventAppender) {
        if (paymentReference == null) {
            throw new IllegalStateException("Payment not prepared yet");
        }
        if (!closed) {
            eventAppender.append(new PaymentRejectedEvent(command.paymentId(), paymentReference));
        }
    }

    @EventSourcingHandler
    public void handle(PaymentPreparedEvent event) {
        this.paymentReference = event.paymentReference();
    }

    @EventSourcingHandler
    protected void on(PaymentConfirmedEvent event) {
        this.closed = true;
    }

    @EventSourcingHandler
    protected void on(PaymentRejectedEvent event) {
        this.closed = true;
    }
}
