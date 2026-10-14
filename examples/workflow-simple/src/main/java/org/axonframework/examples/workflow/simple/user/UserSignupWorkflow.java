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
package org.axonframework.examples.workflow.simple.user;

import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.annotation.Workflow;
import io.axoniq.framework.workflow.annotation.WorkflowCompletedHandler;
import io.axoniq.framework.workflow.dsl.api.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.axoniq.framework.workflow.runtime.association.Associations.associate;
import static io.axoniq.framework.workflow.dsl.api.Payload.payload;
import static io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo;
import static io.axoniq.framework.workflow.runtime.association.PayloadPropertyValueRetriever.payloadProperty;

/**
 * Sample user registration.
 * <p>
 * The {@link #execute(SimpleWorkflowContext) workflow body} reads as the process: create and activate the user, send
 * a welcome email and wait for magic to happen. Each step of the process lives in its own method, which receives the
 * values it needs as arguments and returns the values the process needs next.
 *
 * @since 5.4.0
 */
public class UserSignupWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(UserSignupWorkflow.class);

    @Workflow(
            idProperty = "id",
            startOnEventClass = RegistrationReceivedEvent.class,
            startOnConditions = {"payload:status=vip"},
            workflowName = "MyWorkflow",
            workflowVersion = "1.0.0",
            workflowNamespace = "io.axoniq.dsl.wf.workflow"
    )
    public void execute(SimpleWorkflowContext ctx) {
        logger.info("User signup workflow started at {} for {}", Instant.now(), ctx.workflowPayload());
        var registration = payload(ctx.workflowPayload());
        String userId = registration.get("id");
        String email = registration.get("email");

        if (!createUser(ctx)) {
            return;
        }
        activateUser(ctx, userId);
        sendWelcomeEmail(ctx, email);
        ctx.sleep("waitASecond", Duration.ofSeconds(1L));

        var magic = waitForMagic(ctx);
        logger.info("Magic happened because of the magician {}", magic.magician());
    }

    @WorkflowCompletedHandler
    public void onFinish(WorkflowStatus workflowStatus, SimpleWorkflowContext ctx) {
        logger.info("User signup workflow {} at {} for {}", workflowStatus, Instant.now(), ctx.workflowPayload());
    }

    private boolean createUser(SimpleWorkflowContext ctx) {
        return ctx.awaitExecute("createUser", Boolean.class, UserService::createUser);
    }

    private void activateUser(SimpleWorkflowContext ctx, String userId) {
        ctx.awaitExecute("activateUser",
                         payload("id", userId).getValues(),
                         UserService::activateUser,
                         step -> step.timeout(Duration.ofSeconds(10)));
    }

    private void sendWelcomeEmail(SimpleWorkflowContext ctx, String email) {
        ctx.awaitExecute("sendWelcomeEmail",
                         Map.of(),
                         (pc, input) -> {
                             NotificationService.sendEmail(email);
                             return Map.of();
                         });
    }

    /**
     * Waits for Merlin to make magic happen.
     *
     * @return the magic that happened
     */
    private MagicHappenedEvent waitForMagic(SimpleWorkflowContext ctx) {
        return ctx.awaitEvent("waitForMagicToHappen",
                              MagicHappenedEvent.class,
                              associate(payloadProperty("magician"), equalsTo("Merlin")),
                              step -> step.timeout(Duration.ofSeconds(5)));
    }
}
