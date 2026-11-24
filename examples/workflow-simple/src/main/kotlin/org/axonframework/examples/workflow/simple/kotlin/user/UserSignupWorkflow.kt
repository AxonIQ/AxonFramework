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
import io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo
import io.axoniq.framework.workflow.dsl.kotlin.Kontext
import io.axoniq.framework.workflow.runtime.association.Associations.associate
import io.axoniq.framework.workflow.runtime.association.PayloadPropertyValueRetriever.payloadProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Sample user registration, using the Kotlin DSL.
 */
class UserSignupWorkflow {

    @Workflow(idProperty = "id", startOnEventName = "my.custom.RegistrationReceived")
    fun Kontext.onExecute() {
        logger.info { "User signup workflow started for $payload" }
        val userId = payload["id"] as String

        val userCreated = awaitExecute<Boolean>("createUser", timeout = 5.seconds, retryPolicy = RetryPolicy.NONE) {
            UserService.createUser()
        }
        if (!userCreated) {
            return
        }
        awaitExecute(
            stepName = "activateUser",
            inputPayload = mapOf("id" to userId),
            timeout = 10.seconds,
            retryPolicy = RetryPolicy.NONE,
            action = UserService::activateUser
        )
        awaitExecute<Boolean>("sendWelcomeEmail", timeout = 5.seconds, retryPolicy = RetryPolicy.NONE) {
            NotificationService.sendEmail()
        }

        awaitEvent<EmailConfirmedEvent>(
            "waitForEmailConfirmation",
            associate(payloadProperty("userId"), equalsTo(userId)),
            timeout = 5.seconds
        )
    }
}

fun UserSignupWorkflow.execute(kontext: Kontext) = with(kontext) { onExecute() }
