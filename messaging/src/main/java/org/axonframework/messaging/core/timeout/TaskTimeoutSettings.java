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
 * Used in other parts of the configuration.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
public class TaskTimeoutSettings {

    /**
     * The timeout of the message handler in milliseconds.
     */
    private int timeoutMs = -1;

    /**
     * The threshold in milliseconds after which a warning is logged. Setting this to a value higher than
     * {@code timeout} will disable warnings.
     */
    private int warningThresholdMs = -1;

    /**
     * The interval in milliseconds between warnings.
     */
    private int warningIntervalMs = -1;

    /**
     * Creates a new {@code TaskTimeoutSettings} with default timeout settings.
     * <p>
     * This means all timeouts are disabled.
     */
    public TaskTimeoutSettings() {
    }

    /**
     * Creates a new {@code TaskTimeoutSettings} with the provided timeout settings.
     *
     * @param timeoutMs          the timeout in milliseconds
     * @param warningThresholdMs the threshold in milliseconds after which a warning is logged. Setting this to a value
     *                           higher than or equal to {@code timeout} will disable warnings
     * @param warningIntervalMs  the interval in milliseconds between warnings
     */
    public TaskTimeoutSettings(int timeoutMs,
                               int warningThresholdMs,
                               int warningIntervalMs) {
        this.timeoutMs = timeoutMs;
        this.warningThresholdMs = warningThresholdMs;
        this.warningIntervalMs = warningIntervalMs;
    }

    /**
     * Returns the timeout of the message handler in milliseconds.
     *
     * @return the timeout of the message handler in milliseconds
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Sets the timeout of the message handler in milliseconds.
     *
     * @param timeoutMs the timeout of the message handler in milliseconds
     */
    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns the threshold in milliseconds after which a warning is logged.
     * <p>
     * Setting this to a value higher than {@code timeout} will disable warnings.
     *
     * @return the threshold in milliseconds after which a warning is logged
     */
    public int getWarningThresholdMs() {
        return warningThresholdMs;
    }

    /**
     * Sets the threshold in milliseconds after which a warning is logged.
     * <p>
     * Setting this to a value higher than or equal to {@code timeout} will disable warnings.
     *
     * @param warningThresholdMs the threshold in milliseconds after which a warning is logged
     */
    public void setWarningThresholdMs(int warningThresholdMs) {
        this.warningThresholdMs = warningThresholdMs;
    }

    /**
     * Returns the interval in milliseconds between warnings.
     *
     * @return the interval in milliseconds between warnings
     */
    public int getWarningIntervalMs() {
        return warningIntervalMs;
    }

    /**
     * Sets the interval in milliseconds between warnings.
     *
     * @param warningIntervalMs the interval in milliseconds between warnings
     */
    public void setWarningIntervalMs(int warningIntervalMs) {
        this.warningIntervalMs = warningIntervalMs;
    }

    /**
     * Returns whether these settings are disabled, meaning neither a timeout nor a warning is configured.
     *
     * @return {@code true} if both {@link #getTimeoutMs()} and {@link #getWarningThresholdMs()} are negative,
     * {@code false} otherwise
     */
    public boolean isDisabled() {
        return timeoutMs < 0 && warningThresholdMs < 0;
    }
}
