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

import org.axonframework.examples.workflow.simple.user.SleepUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import java.time.Duration
import java.time.Instant


private val logger = KotlinLogging.logger {}

/**
 * @since 5.4.0
 */
object UserService {
    fun createUser(): Boolean {
        logger.info { "Creating user." }
        return true
    }

    fun activateUser(pc: ProcessingContext, payload: Map<String, Any?>): Map<String, Any?> {
        val now = Instant.now()
        logger.info { "Activating user with id: ${payload["id"]}" }
        SleepUtils.waitWithProgress(1000)
        logger.info { "Activation took ${Duration.between(Instant.now(), now)}." }
        return mapOf()
    }
}
