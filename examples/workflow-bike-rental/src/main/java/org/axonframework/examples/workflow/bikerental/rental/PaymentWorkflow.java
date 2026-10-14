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
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RejectRequestCommand;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo;
import static io.axoniq.framework.workflow.dsl.api.Payload.payload;
import static io.axoniq.framework.workflow.runtime.association.Associations.associate;
import static io.axoniq.framework.workflow.runtime.association.PayloadPropertyValueRetriever.payloadProperty;

/**
 * Workflow that handles the payment of a bike rental. This workflow is a port of the Bike Rental Saga of the Axon
 * Framework 4 example, with the same business logic.
 * <p>
 * The {@link #execute(SimpleWorkflowContext) workflow body} reads as the process: prepare the payment, wait for its
 * outcome and approve or reject the rental request accordingly. Each step of the process lives in its own method,
 * which receives the values it needs as arguments and returns the values the process needs next.
 *
 * @author Simon Zambrovski
 * @since 5.4.0
 */
@Component
public class PaymentWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWorkflow.class);

    private static final int RENTAL_FEE = 10;
    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration PAYMENT_OUTCOME_TIMEOUT = Duration.ofSeconds(240);

    @Workflow(
            startOnEventName = "org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeRequestedEvent",
            idProperty = "bikeId",
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

    /**
     * Prepares the payment, retrying after a delay until the payment is prepared.
     *
     * @return the id of the prepared payment
     */
    private String preparePayment(SimpleWorkflowContext ctx, String paymentReference) {
        // A step name is the durable identity of a step: a step that already ran is not run again, its recorded
        // result is returned instead. Each attempt therefore needs its own step names, suffixed by the attempt.
        for (int attempt = 1; ; attempt++) {
            var paymentId = tryPreparePayment(ctx, paymentReference, attempt);
            if (paymentId.isPresent()) {
                return paymentId.get();
            }
            logger.info("Retrying to prepare payment {} in {} secs.", paymentReference, RETRY_DELAY.toSeconds());
            ctx.sleep("retryPayment" + attempt, RETRY_DELAY);
        }
    }

    /**
     * Sends the prepare payment command and waits for the payment to be prepared.
     *
     * @return the id of the prepared payment, or empty when the command failed or the payment was not prepared in time
     */
    private Optional<String> tryPreparePayment(SimpleWorkflowContext ctx, String paymentReference, int attempt) {
        logger.info("Preparing payment {}, attempt {}", paymentReference, attempt);

        // Wait for the event before sending the command, so the event cannot arrive before the workflow waits.
        var paymentPrepared = waitForPaymentEvent(
                ctx, "paymentPrepared" + attempt, PaymentPreparedEvent.class, paymentReference, STEP_TIMEOUT
        );
        var preparePayment = sendCommand(
                ctx, "preparePayment" + attempt, new PreparePaymentCommand(RENTAL_FEE, paymentReference)
        );

        if (!ctx.allMatch(WorkflowStepResult::success, preparePayment, paymentPrepared).success()) {
            // After a failed command, the event never arrives: stop waiting for it. Cancelling a finished step does
            // nothing.
            paymentPrepared.cancel("Payment not prepared.");
            logger.info("Payment {} not prepared.", paymentReference);
            return Optional.empty();
        }

        var paymentId = paymentPrepared.resultAs(PaymentPreparedEvent.class)
                                       .map(PaymentPreparedEvent::paymentId)
                                       .orElseThrow(() -> new IllegalStateException("No payment prepared event"));
        logger.info("Payment {} prepared with payment id {}", paymentReference, paymentId);
        return Optional.of(paymentId);
    }

    /**
     * Waits for the payment to be confirmed or rejected, whichever comes first. Without either within the
     * {@link #PAYMENT_OUTCOME_TIMEOUT}, the payment expires.
     */
    private PaymentOutcome awaitPaymentOutcome(SimpleWorkflowContext ctx, String paymentReference) {
        logger.info("Waiting for payment confirmation or rejection for the next {} seconds",
                    PAYMENT_OUTCOME_TIMEOUT.toSeconds());
        var confirmed = waitForPaymentEvent(
                ctx, "paymentConfirmed", PaymentConfirmedEvent.class, paymentReference, PAYMENT_OUTCOME_TIMEOUT
        );
        var rejected = waitForPaymentEvent(
                ctx, "paymentRejected", PaymentRejectedEvent.class, paymentReference, PAYMENT_OUTCOME_TIMEOUT
        );
        ctx.anyMatch(WorkflowStepResult::success, confirmed, rejected).await();

        // success() blocks until a step finishes, and the losing wait is still open: check isCompleted() first.
        if (confirmed.isCompleted() && confirmed.success()) {
            rejected.cancel("Payment confirmed.");
            return PaymentOutcome.CONFIRMED;
        }
        if (rejected.isCompleted() && rejected.success()) {
            confirmed.cancel("Payment rejected.");
            return PaymentOutcome.REJECTED;
        }
        return PaymentOutcome.EXPIRED;
    }

    private void approveRequest(SimpleWorkflowContext ctx, String bikeId, String renter) {
        logger.info("Payment confirmed. Approving the request of {} for bike {}.", renter, bikeId);
        sendCommand(ctx, "confirmRequest", new ApproveRequestCommand(bikeId, renter)).await();
    }

    private void rejectRequest(SimpleWorkflowContext ctx, String bikeId, String renter) {
        logger.info("Rejecting the request of {} for bike {}.", renter, bikeId);
        sendCommand(ctx, "rejectRequest", new RejectRequestCommand(bikeId, renter)).await();
    }

    private void rejectPayment(SimpleWorkflowContext ctx, String paymentId) {
        logger.info("Payment {} not confirmed or rejected within {} seconds. Rejecting the payment.",
                    paymentId, PAYMENT_OUTCOME_TIMEOUT.toSeconds());
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

    private enum PaymentOutcome {
        CONFIRMED, REJECTED, EXPIRED
    }
}
