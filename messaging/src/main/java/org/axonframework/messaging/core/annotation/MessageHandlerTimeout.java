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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.core.timeout.HandlerTimeoutConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets the timeout settings of a message handler.
 * <p>
 * Takes precedence over configuration through {@link HandlerTimeoutConfiguration}, for each property individually.
 * <p>
 * Any property set to {@code -1} will use the default value of the {@link HandlerTimeoutConfiguration}. For example,
 * setting the timeout to {@code -1}, and {@code warningThreshold} to {@code 1000}, will take the default timeout from
 * the configuration, while setting the warning threshold to {@code 1000}.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@HasHandlerAttributes
public @interface MessageHandlerTimeout {

    /**
     * The timeout for a single message in milliseconds. A value of {@code -1} indicates that the timeout should be used
     * of the {@link HandlerTimeoutConfiguration}.
     *
     * @return the timeout for a single message handler in milliseconds
     */
    int timeoutMs() default -1;

    /**
     * The time in milliseconds after which a warning message should be logged that the message handler is taking too
     * long. A value of {@code -1} indicates that the default warning threshold should be used.
     * <p>
     * A value higher than the {@code timeout} (or the timeout from the configuration if is {@code -1} on the
     * annotation), will disable warnings.
     *
     * @return the time in milliseconds after which a warning message should be logged
     */
    int warningThresholdMs() default -1;

    /**
     * The interval in milliseconds at which warning messages should be logged that the message handler is taking too
     * long. A value of {@code -1} indicates that the default warning interval should be used of the
     * {@link HandlerTimeoutConfiguration}.
     *
     * @return the interval in milliseconds at which warning log messages should be displayed
     */
    int warningIntervalMs() default -1;
}
