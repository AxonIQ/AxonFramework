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
import org.axonframework.examples.workflow.bikerental.coreapi.payment.GetAllPaymentsQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.GetPaymentIdQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.GetPaymentStatusQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentStatus;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PreparePaymentCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.RejectPaymentCommand;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @since 5.4.0
 */
@RestController
public class PaymentController {

    private final QueryGateway queryGateway;
    private final CommandGateway commandGateway;

    public PaymentController(
            QueryGateway queryGateway,
            CommandGateway commandGateway
    ) {
        this.queryGateway = queryGateway;
        this.commandGateway = commandGateway;
    }

    @PostMapping("/preparePayment")
    public CompletableFuture<String> preparePayment(@RequestParam("amount") int amount,
                                                    @RequestParam("reference") String paymentReference) {
        return commandGateway.send(new PreparePaymentCommand(amount, paymentReference)).resultAs(String.class);
    }

    @GetMapping("/status/{paymentId}")
    public CompletableFuture<PaymentStatus> getStatus(@PathVariable("paymentId") String paymentId) {
        return queryGateway.query(new GetPaymentStatusQuery(paymentId), PaymentStatus.class);
    }

    @GetMapping("/findPayment")
    public CompletableFuture<String> findPaymentId(@RequestParam("reference") String paymentReference) {
        return queryGateway.query(new GetPaymentIdQuery(paymentReference), String.class);
    }

    @PostMapping("/acceptPayment")
    public CompletableFuture<Void> confirmPayment(@RequestParam("id") String paymentId) {
        return commandGateway.send(new ConfirmPaymentCommand(paymentId)).resultAs(Void.class);
    }

    @PostMapping("/rejectPayment")
    public CompletableFuture<Void> rejectPayment(@RequestParam("id") String paymentId) {
        return commandGateway.send(new RejectPaymentCommand(paymentId)).resultAs(Void.class);
    }

    @GetMapping("/status")
    public CompletableFuture<List<PaymentStatus>> getStatus(
            @RequestParam(value = "status", required = false) PaymentStatus.Status status
    ) {
        return queryGateway.queryMany(new GetAllPaymentsQuery(status), PaymentStatus.class);
    }
}
