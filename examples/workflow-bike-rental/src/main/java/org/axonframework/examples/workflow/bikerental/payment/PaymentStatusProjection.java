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

import org.axonframework.examples.workflow.bikerental.coreapi.payment.GetAllPaymentsQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.GetPaymentIdQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.GetPaymentStatusQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentStatus;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import static org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentStatus.Status.PENDING;

/**
 * @since 5.4.0
 */
@Component
public class PaymentStatusProjection {

    private final PaymentStatusRepository paymentStatusRepository;

    public PaymentStatusProjection(PaymentStatusRepository paymentStatusRepository) {
        this.paymentStatusRepository = paymentStatusRepository;
    }

    @QueryHandler(queryName = "org.axonframework.examples.workflow.bikerental.coreapi.payment.getPaymentStatus")
    public PaymentStatus getStatus(GetPaymentStatusQuery q) {
        return paymentStatusRepository.findById(q.paymentId()).orElse(null);
    }

    @QueryHandler(queryName = "org.axonframework.examples.workflow.bikerental.coreapi.payment.getPaymentId")
    public String getPaymentId(GetPaymentIdQuery q) {
        return paymentStatusRepository.findByReferenceAndStatus(q.paymentReference(), PENDING)
                                      .map(PaymentStatus::getId)
                                      .orElse(null);
    }

    @QueryHandler(queryName = "org.axonframework.examples.workflow.bikerental.coreapi.payment.getAllPayments")
    public Iterable<PaymentStatus> findByStatus(GetAllPaymentsQuery q) {
        if (q.status() != null) {
            return paymentStatusRepository.findAllByStatus(q.status());
        }
        return paymentStatusRepository.findAll();
    }
}
