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
package org.axonframework.examples.workflow.simple.kotlin.user

import org.axonframework.examples.workflow.simple.kotlin.user.MagicHappenedEvent
import org.axonframework.examples.workflow.simple.kotlin.user.NotificationService
import org.axonframework.examples.workflow.simple.kotlin.user.UserService
import io.axoniq.framework.workflow.dsl.kotlin.Kontext
import io.axoniq.framework.workflow.runtime.api.annotation.Workflow
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions
import io.axoniq.framework.workflow.runtime.api.execution.context.retry.RetryPolicy
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import kotlin.time.Duration.Companion.seconds


private val logger = KotlinLogging.logger {}

/**
 * @since 5.4.0
 */
class UserSignupWorkflow {

    @Workflow(idProperty = "id", startOnEventName = "my.custom.RegistrationReceived")
    fun Kontext.onExecute() {
        logger.info { "User signup workflow started at ${Instant.now()} for $payload" }

        val success = awaitExecute<Boolean>(
            "createUser",
            timeout = 5.seconds,
            retryPolicy = RetryPolicy.NONE
        ) { UserService.createUser() }

        if (!success) {
            return
        }

        block {
            execute(
                stepName = "activateUser",
                inputPayload = payload,
                timeout = 10.seconds,
                retryPolicy = RetryPolicy.NONE,
                action = UserService::activateUser
            )
        }

        val waitASecond = waitForEvent("waitASecond", EventConditions.never(), timeout = 1.seconds)
        waitASecond.await()
        if (waitASecond.failure() && waitASecond.error().isPresent) {
            throw waitASecond.error().get()
        }

        block {
            execute(
                stepName = "sendWelcomeEmail",
                inputPayload = payload,
                timeout = 5.seconds,
                retryPolicy = RetryPolicy.NONE
            ) { _, _ ->
                NotificationService.sendEmail()
                mapOf()
            }
        }

        val magic = awaitEvent<MagicHappenedEvent>(
            "waitForMagicToHappen",
            timeout = 5.seconds
        )

        logger.info { "Magic happened because of the magician ${magic.magician}" }
        logger.info { "User signup workflow ended at ${Instant.now()} for $payload" }
    }

}

fun UserSignupWorkflow.execute(kontext: Kontext) = with(kontext) { onExecute() }


