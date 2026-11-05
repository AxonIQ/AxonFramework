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
package org.axonframework.examples.workflow.bikerental.rental;

import io.axoniq.framework.workflow.annotation.Workflow;
import io.axoniq.framework.workflow.dsl.api.WorkflowStepResult;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentConfirmedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentPreparedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentRejectedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PreparePaymentCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.RejectPaymentCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.ApproveRequestCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeRequestedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RejectRequestCommand;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo;
import static io.axoniq.framework.workflow.dsl.api.Payload.payload;
import static io.axoniq.framework.workflow.runtime.association.Associations.associate;
import static io.axoniq.framework.workflow.runtime.association.PayloadPropertyValueRetriever.payloadProperty;

/**
 * Payment workflow of a bike rental, ported from the Bike Rental Saga of the Axon Framework 4 example.
 *
 * @author Simon Zambrovski
 */
@Component
public class PaymentWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWorkflow.class);

    private static final int RENTAL_FEE = 10;
    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration PAYMENT_OUTCOME_TIMEOUT = Duration.ofSeconds(240);

    @Workflow(
            startOnEventClass = BikeRequestedEvent.class,
            idProperty = "rentalReference",
            workflowName = "PaymentWorkflow"
    )
    public void execute(SimpleWorkflowContext ctx) {
        var bikeRequest = payload(ctx.workflowPayload());
        String bikeId = bikeRequest.get("bikeId");
        String renter = bikeRequest.get("renter");
        String paymentReference = bikeRequest.get("rentalReference");

        String paymentId = preparePayment(ctx, paymentReference);

        switch (awaitPaymentOutcome(ctx, paymentReference)) {
            case CONFIRMED -> approveRequest(ctx, bikeId, renter);
            case REJECTED -> rejectRequest(ctx, bikeId, renter);
            case EXPIRED -> {
                rejectPayment(ctx, paymentId);
                rejectRequest(ctx, bikeId, renter);
            }
        }
    }

    private String preparePayment(SimpleWorkflowContext ctx, String paymentReference) {
        // A step name identifies a step durably, so each attempt needs its own step names.
        for (int attempt = 1; ; attempt++) {
            // Wait before sending, so the event cannot arrive before the workflow listens.
            var paymentPrepared = waitForPaymentEvent(
                    ctx, "paymentPrepared" + attempt, PaymentPreparedEvent.class, paymentReference, STEP_TIMEOUT
            );
            var preparePayment = sendCommand(
                    ctx, "preparePayment" + attempt, new PreparePaymentCommand(RENTAL_FEE, paymentReference)
            );

            if (ctx.allMatch(WorkflowStepResult::success, preparePayment, paymentPrepared).success()) {
                return paymentPrepared.resultAs(PaymentPreparedEvent.class)
                                      .map(PaymentPreparedEvent::paymentId)
                                      .orElseThrow();
            }
            // allMatch stops at the first failure, so the other step may still be running.
            cancelIfRunning(preparePayment);
            cancelIfRunning(paymentPrepared);
            logger.info("Payment {} not prepared in attempt {}.", paymentReference, attempt);
            ctx.sleep("retryPayment" + attempt, RETRY_DELAY);
        }
    }

    private PaymentOutcome awaitPaymentOutcome(SimpleWorkflowContext ctx, String paymentReference) {
        var confirmed = waitForPaymentEvent(
                ctx, "paymentConfirmed", PaymentConfirmedEvent.class, paymentReference, PAYMENT_OUTCOME_TIMEOUT
        );
        var rejected = waitForPaymentEvent(
                ctx, "paymentRejected", PaymentRejectedEvent.class, paymentReference, PAYMENT_OUTCOME_TIMEOUT
        );
        ctx.anyMatch(WorkflowStepResult::success, confirmed, rejected).await();

        // success() blocks until the step finishes, so check isCompleted() first.
        if (confirmed.isCompleted() && confirmed.success()) {
            cancelIfRunning(rejected);
            return PaymentOutcome.CONFIRMED;
        }
        if (rejected.isCompleted() && rejected.success()) {
            cancelIfRunning(confirmed);
            return PaymentOutcome.REJECTED;
        }
        return PaymentOutcome.EXPIRED;
    }

    private void approveRequest(SimpleWorkflowContext ctx, String bikeId, String renter) {
        sendCommand(ctx, "confirmRequest", new ApproveRequestCommand(bikeId, renter)).await();
    }

    private void rejectRequest(SimpleWorkflowContext ctx, String bikeId, String renter) {
        sendCommand(ctx, "rejectRequest", new RejectRequestCommand(bikeId, renter)).await();
    }

    private void rejectPayment(SimpleWorkflowContext ctx, String paymentId) {
        sendCommand(ctx, "rejectPayment", new RejectPaymentCommand(paymentId)).await();
    }

    private WorkflowStepResult waitForPaymentEvent(SimpleWorkflowContext ctx,
                                                   String stepName,
                                                   Class<?> eventType,
                                                   String paymentReference,
                                                   Duration timeout) {
        return ctx.waitForEvent(
                stepName,
                eventType,
                associate(payloadProperty("paymentReference"), equalsTo(paymentReference)),
                step -> step.timeout(timeout)
        );
    }

    private WorkflowStepResult sendCommand(SimpleWorkflowContext ctx, String stepName, Object command) {
        return ctx.execute(
                stepName,
                (pc, input) -> {
                    CommandDispatcher.forContext(pc)
                                     .send(command)
                                     .getResultMessage()
                                     .orTimeout(2, TimeUnit.SECONDS)
                                     .join();
                    return Map.of();
                },
                step -> step.timeout(STEP_TIMEOUT)
        );
    }

    private static void cancelIfRunning(WorkflowStepResult step) {
        if (!step.isCompleted()) {
            step.cancel();
        }
    }

    private enum PaymentOutcome {
        CONFIRMED, REJECTED, EXPIRED
    }
}
