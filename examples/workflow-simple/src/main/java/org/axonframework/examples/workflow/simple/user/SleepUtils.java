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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class SleepUtils {

    private static final long PROGRESS_INTERVAL_MILLIS = 200;

    static Logger logger = LoggerFactory.getLogger(SleepUtils.class);

    /**
     * Waits for the requested duration and logs progress every 200 milliseconds.
     *
     * @param millis total timeout in milliseconds
 * @since 5.4.0
     */
    public static void waitWithProgress(long millis) {
        try {
            for (long elapsed = 0; elapsed < millis; elapsed += PROGRESS_INTERVAL_MILLIS) {
                logger.info("Waiting for {} / {} millis.", elapsed, millis);
                Thread.sleep(Math.min(PROGRESS_INTERVAL_MILLIS, millis - elapsed));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Takes a nap.
     *
     * @param millis thread sleep timeout.
     */
    public static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Takes a nap.
     *
     * @param duration timeout to sleep.
     */
    public static void sleepQuietly(Duration duration) {
        sleepQuietly(duration.toMillis());
    }
}
