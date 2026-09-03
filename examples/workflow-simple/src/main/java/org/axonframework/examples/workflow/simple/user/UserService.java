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

import org.jspecify.annotations.Nullable;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.axonframework.examples.workflow.simple.user.SleepUtils.waitWithProgress;

/**
 * @since 5.4.0
 */
public class UserService {

    static Logger logger = LoggerFactory.getLogger(UserService.class);

    public static boolean createUser() {
        logger.info("Creating user.");
        return true;
    }

    public static Map<String, @Nullable Object> activateUser(ProcessingContext pc, Map<String, @Nullable Object> payload) {
        Instant now = Instant.now();
        logger.info("Activating user with id: {}", payload.get("id"));
        waitWithProgress(1_000);
        logger.info("Activation took {}.", Duration.between(Instant.now(), now));
        return Map.of();
    }
}
