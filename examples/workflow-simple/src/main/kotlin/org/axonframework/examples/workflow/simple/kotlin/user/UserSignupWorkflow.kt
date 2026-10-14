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

import io.axoniq.framework.workflow.annotation.Workflow
import io.axoniq.framework.workflow.dsl.api.retry.RetryPolicy
import io.axoniq.framework.workflow.dsl.kotlin.Kontext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Sample user registration, using the Kotlin DSL.
 *
 * The workflow body reads as the process: create and activate the user, send a welcome email and wait for magic to
 * happen. Each step of the process lives in its own function, which receives the values it needs as arguments and
 * returns the values the process needs next.
 *
 * @since 5.4.0
 */
class UserSignupWorkflow {

    @Workflow(idProperty = "id", startOnEventName = "my.custom.RegistrationReceived")
    fun Kontext.onExecute() {
        logger.info { "User signup workflow started at ${Instant.now()} for $payload" }
        val userId = payload["id"] as String

        if (!createUser()) {
            return
        }
        activateUser(userId)
        sleep("waitASecond", 1.seconds)
        sendWelcomeEmail()

        val magic = waitForMagic()
        logger.info { "Magic happened because of the magician ${magic.magician}" }
        logger.info { "User signup workflow ended at ${Instant.now()} for $payload" }
    }

    private fun Kontext.createUser(): Boolean =
        awaitExecute<Boolean>("createUser", timeout = 5.seconds, retryPolicy = RetryPolicy.NONE) {
            UserService.createUser()
        }

    private fun Kontext.activateUser(userId: String) = block {
        execute(
            stepName = "activateUser",
            inputPayload = mapOf("id" to userId),
            timeout = 10.seconds,
            retryPolicy = RetryPolicy.NONE,
            action = UserService::activateUser
        )
    }

    private fun Kontext.sendWelcomeEmail() = block {
        execute(
            stepName = "sendWelcomeEmail",
            timeout = 5.seconds,
            retryPolicy = RetryPolicy.NONE
        ) { _, _ ->
            NotificationService.sendEmail()
            mapOf()
        }
    }

    private fun Kontext.waitForMagic(): MagicHappenedEvent =
        awaitEvent<MagicHappenedEvent>("waitForMagicToHappen", timeout = 5.seconds)
}

fun UserSignupWorkflow.execute(kontext: Kontext) = with(kontext) { onExecute() }
