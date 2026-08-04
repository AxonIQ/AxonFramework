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

package org.axonframework.messaging.core;

import org.axonframework.common.AxonException;
import org.axonframework.common.TypeReference;
import org.axonframework.common.annotation.Internal;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Base exception for exceptions raised by Handler methods. Besides standard exception information (such as message and
 * cause), these exception may optionally carry an object with additional application-specific details about the
 * exception.
 * <p/>
 * By default, a stack trace is not generated for this exception. However, the stack trace creation can be enforced
 * explicitly via the constructor accepting the {@code writableStackTrace} parameter.
 * <p/>
 * When these details cross an infrastructure boundary (for example, a remote command or query dispatched through Axon
 * Server), they may arrive as raw data alongside a {@link Converter} rather than as the original application object. In
 * that case, {@link #getDetails(Class)}, {@link #getDetails(TypeReference)}, and {@link #getDetails(Type)} apply the
 * {@link Converter} lazily, on request, mirroring how {@link Message#payloadAs(Class)} defers payload conversion. This
 * keeps the thrower and the receiver decoupled from having to agree on the exact same details class or version.
 *
 * @author Allard Buize
 * @since 4.2.0
 */
public abstract class HandlerExecutionException extends AxonException {

    private final @Nullable Object details;
    private final @Nullable Converter converter;

    /**
     * Initializes an execution exception with given {@code message}. The cause and application-specific details are set
     * to {@code null}.
     *
     * @param message a message describing the exception
     */
    public HandlerExecutionException(String message) {
        this(message, null, null);
    }

    /**
     * Initializes an execution exception with given {@code message} and {@code cause}. The application-specific details
     * are set to {@code null}.
     *
     * @param message a message describing the exception
     * @param cause   the cause of the execution exception
     */
    public HandlerExecutionException(String message,
                                     @Nullable Throwable cause) {
        this(message, cause, resolveDetails(cause).orElse(null));
    }

    /**
     * Initializes an execution exception with given {@code message}, {@code cause} and application-specific
     * {@code details}.
     *
     * @param message a message describing the exception
     * @param cause   the cause of the execution exception
     * @param details an object providing application-specific details of the exception
     */
    public HandlerExecutionException(String message,
                                     @Nullable Throwable cause,
                                     @Nullable Object details) {
        this(message, cause, details, false);
    }

    /**
     * Initializes an execution exception with given {@code message}, {@code cause}, application-specific
     * {@code details}, and {@code writableStackTrace}
     *
     * @param message            a message describing the exception
     * @param cause              the cause of the execution exception
     * @param details            an object providing application-specific details of the exception
     * @param writableStackTrace whether the stack trace should be generated ({@code true}) or not ({@code false})
     */
    public HandlerExecutionException(String message,
                                     @Nullable Throwable cause,
                                     @Nullable Object details,
                                     boolean writableStackTrace) {
        this(message, cause, details, null, writableStackTrace);
    }

    /**
     * Initializes an execution exception with given {@code message}, {@code cause}, application-specific
     * {@code details}, {@code converter}, and {@code writableStackTrace}.
     * <p/>
     * This constructor is used by messaging infrastructure to attach a {@link Converter} when reconstructing
     * {@code details} from raw data received over an infrastructure boundary (for example, a remote command or query
     * response). It is not meant to be used directly by application code, which typically already has the details
     * object in its final form and can use {@link #HandlerExecutionException(String, Throwable, Object)} instead.
     *
     * @param message            a message describing the exception
     * @param cause              the cause of the execution exception
     * @param details            an object providing application-specific details of the exception, potentially raw data
     *                           awaiting conversion
     * @param converter          the {@link Converter} to lazily apply when {@link #getDetails(Class)},
     *                           {@link #getDetails(TypeReference)}, or {@link #getDetails(Type)} is called with a type
     *                           {@code details} does not already match, or {@code null} if no such conversion is
     *                           available
     * @param writableStackTrace whether the stack trace should be generated ({@code true}) or not ({@code false})
     */
    @Internal
    public HandlerExecutionException(String message,
                                     @Nullable Throwable cause,
                                     @Nullable Object details,
                                     @Nullable Converter converter,
                                     boolean writableStackTrace) {
        super(message, cause, writableStackTrace);
        this.details = details;
        this.converter = converter;
    }

    /**
     * Resolve details from the given {@code throwable}, taking into account that the details may be available in any of
     * the {@code HandlerExecutionExceptions} in the "cause" chain.
     *
     * @param throwable the exception to resolve the details from
     * @param <R>       the type of details expected
     * @return an {@code Optional} containing details, if present in the given {@code throwable}
     */
    public static <R> Optional<R> resolveDetails(@Nullable Throwable throwable) {
        if (throwable instanceof HandlerExecutionException) {
            return ((HandlerExecutionException) throwable).getDetails();
        } else if (throwable != null && throwable.getCause() != null) {
            return resolveDetails(throwable.getCause());
        }
        return Optional.empty();
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, if any were provided.
     * <p>
     * These details are implicitly cast to the expected type. A mismatch in type may lead to a
     * {@link ClassCastException} further downstream, when accessing the {@code Optional's} enclosed value.
     *
     * @param <R> the type of details expected
     * @return an Optional containing the details, if provided
     * @deprecated in favor of {@link #getDetails(Class)}, or any of the {@link Type}, {@link TypeReference}, or
     * {@link Converter} parameter combinations, as those ensure the details are converted to the desired type
     */
    @Deprecated(since = "5.3.0", forRemoval = true)
    @SuppressWarnings("unchecked")
    public <R> Optional<R> getDetails() {
        return Optional.ofNullable((R) details);
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, converted into the given
     * {@code type} if necessary, using the {@link Converter} attached to this exception (if any).
     *
     * @param type the type to return the details as
     * @param <R>  the type of details expected
     * @return an {@code Optional} containing the details converted to {@code type}, if provided
     * @throws ConversionException if the details are present, do not match {@code type}, and either no
     *                             {@link Converter} is available or the conversion fails
     */
    public <R> Optional<R> getDetails(Class<R> type) {
        return getDetails(type, this.converter);
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, converted into the given
     * {@code type} if necessary, using the given {@code converter}.
     * <p/>
     * If the current details already are an instance of {@code type}, no conversion takes place and the given
     * {@code converter} is ignored. Otherwise, the given {@code converter} is used to convert the details into
     * {@code type}. This allows the thrower and the receiver of these details to use different classes or versions for
     * the same logical details.
     *
     * @param type      the type to return the details as
     * @param converter the converter to convert the details with, or {@code null} if no conversion is available
     * @param <R>       the type of details expected
     * @return an {@code Optional} containing the details converted to {@code type}, if provided
     * @throws ConversionException if the details are present, do not match {@code type}, and either no
     *                             {@code converter} is given or the conversion fails
     */
    public <R> Optional<R> getDetails(Class<R> type, @Nullable Converter converter) {
        return getDetails((Type) type, converter);
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, converted into the given
     * {@code type} if necessary, using the {@link Converter} attached to this exception (if any).
     * <p/>
     * Behaves identically to {@link #getDetails(Class)}, but supports generic types (for example {@code List<String>})
     * through a {@link TypeReference}.
     *
     * @param type the type to return the details as
     * @param <R>  the type of details expected
     * @return an {@code Optional} containing the details converted to {@code type}, if provided
     * @throws ConversionException if the details are present, do not match {@code type}, and either no
     *                             {@link Converter} is available or the conversion fails
     */
    public <R> Optional<R> getDetails(TypeReference<R> type) {
        return getDetails(type, this.converter);
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, converted into the given
     * {@code type} if necessary, using the given {@code converter}.
     * <p/>
     * Behaves identically to {@link #getDetails(Class, Converter)}, but supports generic types (for example
     * {@code List<String>}) through a {@link TypeReference}.
     *
     * @param type      the type to return the details as
     * @param converter the converter to convert the details with, or {@code null} if no conversion is available
     * @param <R>       the type of details expected
     * @return an {@code Optional} containing the details converted to {@code type}, if provided
     * @throws ConversionException if the details are present, do not match {@code type}, and either no
     *                             {@code converter} is given or the conversion fails
     */
    public <R> Optional<R> getDetails(TypeReference<R> type, @Nullable Converter converter) {
        return getDetails(type.getType(), converter);
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, converted into the given
     * {@code type} if necessary, using the {@link Converter} attached to this exception (if any).
     * <p/>
     * Behaves identically to {@link #getDetails(Class)}, but accepts a raw {@link Type}, for callers that already have
     * one at hand (for example, obtained through reflection) instead of a {@link Class} or {@link TypeReference}.
     *
     * @param type the type to return the details as
     * @param <R>  the type of details expected
     * @return an {@code Optional} containing the details converted to {@code type}, if provided
     * @throws ConversionException if the details are present, do not match {@code type}, and either no
     *                             {@link Converter} is available or the conversion fails
     */
    public <R> Optional<R> getDetails(Type type) {
        return getDetails(type, this.converter);
    }

    /**
     * Returns an {@code Optional} containing application-specific details of the exception, converted into the given
     * {@code type} if necessary, using the given {@code converter}.
     * <p/>
     * If the current details already are an instance of {@code type}, no conversion takes place and the given
     * {@code converter} is ignored. Otherwise, the given {@code converter} is used to convert the details into
     * {@code type}. This allows the thrower and the receiver of these details to use different classes or versions for
     * the same logical details. This is the terminal operation every other {@code getDetails} overload delegates to.
     *
     * @param type      the type to return the details as
     * @param converter the converter to convert the details with, or {@code null} if no conversion is available
     * @param <R>       the type of details expected
     * @return an {@code Optional} containing the details converted to {@code type}, if provided
     * @throws ConversionException if the details are present, do not match {@code type}, and either no
     *                             {@code converter} is given or the conversion fails
     */
    public <R> Optional<R> getDetails(Type type, @Nullable Converter converter) {
        if (details == null) {
            return Optional.empty();
        }
        if (TypeReference.fromType(type).getTypeAsClass().isInstance(details)) {
            //noinspection unchecked
            return Optional.of((R) details);
        }
        if (converter == null) {
            throw new ConversionException(
                    "Cannot convert details of type [" + details.getClass().getName() + "] to [" + type
                            + "] without a Converter."
            );
        }
        return Optional.ofNullable(converter.convert(details, type));
    }
}