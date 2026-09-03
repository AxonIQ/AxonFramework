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

import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentConfirmedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentPreparedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentRejectedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentStatus;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.springframework.stereotype.Component;

import static org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentStatus.Status.APPROVED;
import static org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentStatus.Status.REJECTED;

/**
 * @since 5.4.0
 */
@Component
public class PaymentStatusProjector {

    private final PaymentStatusRepository paymentStatusRepository;

    public PaymentStatusProjector(PaymentStatusRepository paymentStatusRepository) {
        this.paymentStatusRepository = paymentStatusRepository;
    }

    @EventHandler
    public void handle(PaymentPreparedEvent event, QueryUpdateEmitter updateEmitter) {
        paymentStatusRepository.save(new PaymentStatus(event.paymentId(), event.amount(), event.paymentReference()));
        updateEmitter.emit(String.class, event.paymentReference()::equals, event.paymentId());
    }

    @EventHandler
    public void handle(PaymentConfirmedEvent event) {
        paymentStatusRepository.findById(event.paymentId()).ifPresent(s -> s.status = APPROVED);
    }

    @EventHandler
    public void handle(PaymentRejectedEvent event) {
        paymentStatusRepository.findById(event.paymentId()).ifPresent(s -> s.status = REJECTED);
    }
}
