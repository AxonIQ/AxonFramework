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

import org.jspecify.annotations.Nullable;

import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentConfirmedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentPreparedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PaymentRejectedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.PreparePaymentCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.payment.RejectPaymentCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.ApproveRequestCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RejectRequestCommand;
import io.axoniq.framework.workflow.dsl.api.Payload;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.runtime.api.annotation.Workflow;
import io.axoniq.framework.workflow.runtime.api.execution.state.WorkflowStepResult;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static io.axoniq.framework.workflow.runtime.association.Associations.associate;
import static io.axoniq.framework.workflow.dsl.api.Payload.payload;
import static io.axoniq.framework.workflow.dsl.base.BaseWorkflowContext.equalsTo;
import static io.axoniq.framework.workflow.runtime.association.PayloadPropertyValueRetriever.payloadProperty;

/**
 * Workflow that handles the payment process. This workflow is a port of the famous Bike Rental Saga taken from AF4
 * example and is migrated without changes to business logic implemented there.
 *
 * @author Simon Zambrovski
 * @since 5.4.0
 */
@Component
public class PaymentWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PaymentWorkflow.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    @Workflow(
            startOnEventName = "org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeRequestedEvent",
            idProperty = "bikeId",
            workflowName = "PaymentWorkflow"
    )
    public void execute(SimpleWorkflowContext ctx) {

        ctx.awaitModifyPayload(
                "setAmountAndReference",
                workflowPayload -> payload(workflowPayload)
                        .with(payload(
                                "amount", 10,
                                "paymentReference", ctx.workflowPayload().get("rentalReference")
                        ))
                        .getValues()
        );

        var paymentReference = ctx.workflowPayload().get("paymentReference");

        AtomicBoolean paymentPending = new AtomicBoolean(true);
        while (paymentPending.get()) {

            logger.info("Preparing payment {}", paymentReference);

            var paymentPrepared = ctx.waitForEvent(
                    "paymentPrepared",
                    PaymentPreparedEvent.class,
                    associate(payloadProperty("paymentReference"), equalsTo(paymentReference)),
                    step -> step.timeout(DEFAULT_TIMEOUT)
            );

            var preparePayment = sendCommand(ctx,
                                             "preparePayment",
                                             payload ->
                                                     new PreparePaymentCommand(
                                                             payload.get("amount"),
                                                             payload.get("paymentReference")
                                                     )
            );
            preparePayment.await(); // wait for it

            if (preparePayment.success()) {
                paymentPrepared.await();
                if (paymentPrepared.success()) {
                    paymentPending.set(false);
                    var paymentDetails = paymentPrepared.<Map<String, @Nullable Object>>result()
                                                        .orElseThrow(() -> new IllegalStateException("No payload"));
                    ctx.awaitModifyPayload(
                            "setPaymentId",
                            workflowPayload -> payload(workflowPayload).with(payload(paymentDetails)).getValues()
                    );

                    logger.info("Payment prepared successfully for reference {}, the payment details are {}",
                                paymentReference,
                                paymentDetails);
                } else {
                    logger.info("Did not receive payment prepared event. Retrying in {} secs.",
                                DEFAULT_TIMEOUT.toSeconds());
                    ctx.sleep("retryPayment",
                              step -> step.timeout(DEFAULT_TIMEOUT));
                }
            } else {
                // NICE TO HAVE
                logger.info("Prepare payment command failed. Retrying in {} secs.", DEFAULT_TIMEOUT.toSeconds());
                paymentPrepared.cancel("Prepare payment command failed.");
                ctx.sleep("retryPayment",
                          step -> step.timeout(DEFAULT_TIMEOUT));
            }
        }

        var timeout = 240;
        logger.info("Waiting for payment confirmation or rejection for the next {} seconds", timeout);
        var paymentStatus = ctx.anyMatch(
                WorkflowStepResult::success,
                ctx.waitForEvent(
                        "paymentConfirmed",
                        PaymentConfirmedEvent.class,
                        associate(payloadProperty("paymentReference"), equalsTo(paymentReference)),
                        step -> step.timeout(Duration.ofSeconds(timeout))
                ),
                ctx.waitForEvent(
                        "paymentRejected",
                        PaymentRejectedEvent.class,
                        associate(payloadProperty("paymentReference"), equalsTo(paymentReference)),
                        step -> step.timeout(Duration.ofSeconds(timeout))
                )
        );
        paymentStatus.await();

        if (!paymentStatus.matched().isEmpty()) {
            switch (paymentStatus.matched().getFirst().getStepName()) {
                case "paymentConfirmed":
                    logger.info("Payment confirmed. Approving the request.");
                    sendCommand(ctx,
                                "confirmRequest",
                                payload -> new ApproveRequestCommand(
                                        payload.get("bikeId"),
                                        payload.get("renter")
                                )
                    ).await();
                    break;
                case "paymentRejected":
                    logger.info("Payment rejected. Rejecting the request.");
                    sendCommand(ctx,
                                "rejectRequest",
                                payload -> new RejectRequestCommand(
                                        payload.get("bikeId"),
                                        payload.get("renter")
                                )
                    ).await();
                    break;
            }
        } else {
            logger.info("Payment not confirmed or rejected within {} seconds. Rejecting the request.", timeout);
            // timeout
            sendCommand(
                    ctx,
                    "rejectPayment",
                    payload -> new RejectPaymentCommand(
                            payload.get("paymentId")
                    )
            ).await();
        }
    }

    private WorkflowStepResult sendCommand(
            SimpleWorkflowContext ctx,
            String stepName,
            Function<Payload, Object> commandSupplier) {
        return ctx.execute(
                stepName,
                ctx.workflowPayload(),
                (pc, p) -> {
                    var commandDispatcher = CommandDispatcher.forContext(pc);
                    var payload = payload(p);
                    commandDispatcher.send(commandSupplier.apply(payload))
                                     .getResultMessage()
                                     .orTimeout(2, TimeUnit.SECONDS)
                                     .join();
                    return Map.of();
                },
                step -> step.timeout(DEFAULT_TIMEOUT)
        );
    }
}
