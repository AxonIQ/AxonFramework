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

package org.axonframework.messaging.commandhandling;

import org.axonframework.common.annotation.Internal;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.HandlerExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Indicates that an exception has occurred while handling a command. Typically, this class is used to wrap checked
 * exceptions that have been thrown from a Command Handler while processing an incoming command.
 * <p/>
 * By default, a stack trace is not generated for this exception. However, the stack trace creation can be enforced
 * explicitly via the constructor accepting the {@code writableStackTrace} parameter.
 *
 * @author Allard Buijze
 * @since 1.3.0
 */
public class CommandExecutionException extends HandlerExecutionException {

    /**
     * Initializes the exception with given {@code message} and {@code cause}.
     *
     * @param message The message describing the exception.
     * @param cause   The cause of the exception.
     */
    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Initializes the exception with given {@code message}, {@code cause} and an object providing application-specific
     * {@code details}.
     *
     * @param message the message describing the exception
     * @param cause   the cause of the exception
     * @param details an object providing more error details (might be {@code null})
     */
    public CommandExecutionException(String message,
                                     Throwable cause,
                                     @Nullable Object details) {
        super(message, cause, details);
    }

    /**
     * Initializes the exception with given {@code message}, {@code cause}, an object providing application-specific
     * {@code details}, and {@code writableStackTrace}
     *
     * @param message            the message describing the exception
     * @param cause              the cause of the exception
     * @param details            an object providing more error details (might be {@code null})
     * @param writableStackTrace whether the stack trace should be generated ({@code true}) or not ({@code false})
     */
    public CommandExecutionException(String message,
                                     Throwable cause,
                                     @Nullable Object details,
                                     boolean writableStackTrace) {
        super(message, cause, details, writableStackTrace);
    }

    /**
     * Initializes the exception with given {@code message}, {@code cause}, an object providing application-specific
     * {@code details}, a {@code converter}, and {@code writableStackTrace}.
     * <p/>
     * This constructor is used by messaging infrastructure to attach a {@link Converter} when reconstructing
     * {@code details} from raw data received over an infrastructure boundary (for example, a command dispatched through
     * Axon Server). It is not meant to be used directly by application code, which typically already has the details
     * object in its final form and can use {@link #CommandExecutionException(String, Throwable, Object)} instead.
     *
     * @param message            the message describing the exception
     * @param cause              the cause of the exception
     * @param details            an object providing more error details, potentially raw data awaiting conversion (maybe
     *                           {@code null})
     * @param converter          the {@link Converter} to lazily apply when {@link #getDetails(Class)} is called with a
     *                           type {@code details} does not already match, or {@code null} if no such conversion is
     *                           available
     * @param writableStackTrace whether the stack trace should be generated ({@code true}) or not ({@code false})
     */
    @Internal
    public CommandExecutionException(String message,
                                     Throwable cause,
                                     @Nullable Object details,
                                     @Nullable Converter converter,
                                     boolean writableStackTrace) {
        super(message, cause, details, converter, writableStackTrace);
    }
}