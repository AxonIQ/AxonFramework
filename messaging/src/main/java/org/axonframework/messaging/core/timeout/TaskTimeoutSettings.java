/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.axonframework.messaging.core.timeout;

/**
 * Configuration properties for a task timeout.
 * <p>
 * Used in other parts of the configuration. For a fully disabled configuration, use the
 * {@link TaskTimeoutSettings#DISABLED} constant.
 *
 * @param timeoutMs          the timeout of the message handler in milliseconds
 * @param warningThresholdMs the threshold in milliseconds after which a warning is logged. Setting this to a value
 *                           higher than or equal to {@code timeoutMs} will disable warnings
 * @param warningIntervalMs  the interval in milliseconds between warnings
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 4.11.0
 */
public record TaskTimeoutSettings(
        int timeoutMs,
        int warningThresholdMs,
        int warningIntervalMs
) {

    /**
     * A {@code TaskTimeoutSettings} with all timeouts disabled.
     */
    public static final TaskTimeoutSettings DISABLED = new TaskTimeoutSettings(-1, -1, -1);

    /**
     * Defines the timeout of the message handler in milliseconds.
     *
     * @param timeoutMs the timeout of the message handler in milliseconds
     * @return a new settings instance, for fluent interfacing
     */
    public TaskTimeoutSettings timeoutMs(int timeoutMs) {
        return new TaskTimeoutSettings(timeoutMs, warningThresholdMs, warningIntervalMs);
    }

    /**
     * Defines the threshold in milliseconds after which a warning is logged.
     * <p>
     * Setting this to a value higher than or equal to {@code timeoutMs} will disable warnings.
     *
     * @param warningThresholdMs the threshold in milliseconds after which a warning is logged
     * @return a new settings instance, for fluent interfacing
     */
    public TaskTimeoutSettings warningThresholdMs(int warningThresholdMs) {
        return new TaskTimeoutSettings(timeoutMs, warningThresholdMs, warningIntervalMs);
    }

    /**
     * Defines the interval in milliseconds between warnings.
     *
     * @param warningIntervalMs the interval in milliseconds between warnings
     * @return a new settings instance, for fluent interfacing
     */
    public TaskTimeoutSettings warningIntervalMs(int warningIntervalMs) {
        return new TaskTimeoutSettings(timeoutMs, warningThresholdMs, warningIntervalMs);
    }

    /**
     * Returns whether these settings are disabled, meaning neither a timeout nor a warning is configured.
     *
     * @return {@code true} if both {@link #timeoutMs()} and {@link #warningThresholdMs()} are negative, {@code false}
     * otherwise
     */
    public boolean isDisabled() {
        return timeoutMs < 0 && warningThresholdMs < 0;
    }
}
