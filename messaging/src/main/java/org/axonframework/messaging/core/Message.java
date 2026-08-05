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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.TypeReference;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Representation of a {@link Message}, containing a {@link MessageType type}, payload, and
 * {@link Metadata}.
 * <p>
 * Typical examples of a {@code Messages} are {@link CommandMessage commands},
 * {@link EventMessage events}, and
 * {@link QueryMessage queries}.
 * <p>
 * Instead of implementing {@code Message} directly, consider implementing {@code CommandMessage}, {@code EventMessage}
 * or {@code QueryMessage} instead.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @see CommandMessage
 * @see EventMessage
 * @see QueryMessage
 * @since 2.0.0
 */
public interface Message {

    /**
     * The {@link Context.ResourceKey} used to store and retrieve the {@link Message} from the
     * {@link ProcessingContext}. Should always be the message for which a handler is being called. For example, if an
     * event handler is called within the context of a command, the message should be the event message.
     */
    Context.ResourceKey<Message> RESOURCE_KEY = Context.ResourceKey.withLabel("Message");

    /**
     * Adds the given {@code message} to the given {@code context} under the {@link #RESOURCE_KEY}. This allows
     * retrieving the message from the context later on, for example in a message handler.
     * <p>
     * Note that as the {@link ProcessingContext} might not be mutable in all implementations, this method returns a new
     * {@link ProcessingContext} instance which should be used in place of the original.
     *
     * @param context The {@link ProcessingContext} to which the {@code message} should be added.
     * @param message The {@code Message} to add to the {@code context}.
     * @return The updated {@link ProcessingContext} with the {@code message} added under the {@link #RESOURCE_KEY}.
     */
        static ProcessingContext addToContext(ProcessingContext context, Message message) {
        return context.withResource(RESOURCE_KEY, message);
    }

    /**
     * Retrieves the {@code Message} from the given {@code context} using the {@link #RESOURCE_KEY}.
     *
     * @param context The {@link ProcessingContext} from which to retrieve the {@code Message}.
     * @return The {@code Message} stored in the {@code context} under the {@link #RESOURCE_KEY}, or {@code null} if not
     * found.
     */
    static @Nullable Message fromContext(ProcessingContext context) {
        return context.getResource(RESOURCE_KEY);
    }

    /**
     * Returns the identifier of this {@code Message}.
     * <p>
     * Two messages with the same identifiers should be interpreted as different representations of the same conceptual
     * message. In such case, the {@link Message#metadata() metadata} may be different for both representations. The
     * {@link Message#payload() payload} <em>may</em> be identical.
     *
     * @return The unique identifier of this {@code Message}.
     */
    String identifier();

    /**
     * Returns the message {@link MessageType type} of this {@code Message}.
     *
     * @return The message {@link MessageType type} of this {@code Message}.
     */
    MessageType type();

    /**
     * Returns the payload of this {@code Message}.
     * <p>
     * The payload is the application-specific information.
     *
     * @return The payload of this {@code Message}.
     */
    @Nullable
    Object payload();

    /**
     * Returns the payload of this {@code Message}, converted to the given {@code type} by the given {@code converter}.
     * <p>
     * If {@link #payloadType()} is {@link Class#isAssignableFrom(Class) assignable from} the given {@code type},
     * {@link #payload()} may be invoked instead of using the given {@code converter}.
     * <p>
     * Implementers of this operation may optimize by storing the converted payloads, thus saving a
     * {@link Converter#convert(Object, Class)} invocation in the process. Only when this optimization is in place will
     * a {@code null converter} result in a successful invocation of this method.
     *
     * @param type      The type to convert this {@code Message's} payload to.
     * @param converter The converter to convert this {@code Message's} payload with.
     * @param <T>       The generic type to convert this {@code Message's} payload to.
     * @return The payload of this {@code Message}, converted to the given {@code type}.
     * @throws ConversionException When {@link Converter#convert(Object, Class) conversion} is mandatory but no
     *                             {@code converter} is given.
     */
    @Nullable
    default <T> T payloadAs(Class<T> type, @Nullable Converter converter) {
        return payloadType().isAssignableFrom(type) ? type.cast(payload()) : payloadAs((Type) type, converter);
    }

    /**
     * Returns the payload of this {@code Message} casted to the given type if {@link #payloadType()} is
     * {@link Class#isAssignableFrom(Class) assignable from} the given {@code type}, otherwise throws a
     * {@link ConversionException}.
     *
     * @param type The type to convert this {@code Message's} payload to.
     * @param <T>  The generic type to convert this {@code Message's} payload to.
     * @return The payload of this {@code Message}, converted to the given {@code type}.
     * @throws ConversionException When the given type is not compatible with the payload type.
     */
    @Nullable
    default <T> T payloadAs(Class<T> type) {
        return payloadAs(type, null);
    }

    /**
     * Returns the payload of this {@code Message}, converted to the given {@code type} by the given {@code converter}.
     * <p>
     * If {@link #payloadType()} is {@link Class#isAssignableFrom(Class) assignable from} the given
     * {@link TypeReference#getType()}, {@link #payload()} may be invoked instead of using the given {@code converter}.
     * <p>
     * Implementers of this operation may optimize by storing the converted payloads, thus saving a
     * {@link Converter#convert(Object, Class)} invocation in the process. Only when this optimization is in place will
     * a {@code null converter} result in a successful invocation of this method.
     *
     * @param type      The type to convert this {@code Message's} payload to.
     * @param converter The converter to convert this {@code Message's} payload with.
     * @param <T>       The generic type to convert this {@code Message's} payload to.
     * @return The payload of this {@code Message}, converted to the given {@code type}.
     * @throws ConversionException When {@link Converter#convert(Object, Class) conversion} is mandatory but no
     *                             {@code converter} is given.
     */
    @Nullable
    default <T> T payloadAs(TypeReference<T> type, @Nullable Converter converter) {
        return payloadAs(type.getType(), converter);
    }

    /**
     * Returns the payload of this {@code Message} casted to the given type if {@link #payloadType()} is
     * {@link Class#isAssignableFrom(Class) assignable from} the given {@code type}, otherwise throws a
     * {@link ConversionException}.
     *
     * @param type The type to convert this {@code Message's} payload to.
     * @param <T>  The generic type to convert this {@code Message's} payload to.
     * @return The payload of this {@code Message}, converted to the given {@code type}.
     * @throws ConversionException When the given type is not compatible with the payload type.
     */
    @Nullable
    default <T> T payloadAs(TypeReference<T> type) {
        Type targetType = type.getType();
        return targetType instanceof Class<?>
                ? payloadAs(type.getTypeAsClass())
                : payloadAs(targetType, null);
    }

    /**
     * Returns the payload of this {@code Message}, converted to the given {@code type} by the given {@code converter}.
     * <p>
     * If the given {@code type} is an instance of {@link Class} and {@link #payloadType()} is
     * {@link Class#isAssignableFrom(Class) assignable from} that {@code Class}, {@link #payload()} may be invoked
     * instead of using the given {@code converter}.
     * <p>
     * Implementers of this operation may optimize by storing the converted payloads, thus saving a
     * {@link Converter#convert(Object, Class)} invocation in the process. Only when this optimization is in place will
     * a {@code null converter} result in a successful invocation of this method.
     *
     * @param type      The type to convert this {@code Message's} payload to.
     * @param converter The converter to convert this {@code Message's} payload with.
     * @param <T>       The generic type to convert this {@code Message's} payload to.
     * @return The payload of this {@code Message}, converted to the given {@code type}.
     * @throws ConversionException When {@link Converter#convert(Object, Class) conversion} is mandatory but no
     *                             {@code converter} is given.
     */
    @Nullable
    <T> T payloadAs(Type type, @Nullable Converter converter);

    /**
     * Returns the type of the payload.
     * <p>
     * Is semantically equal to {@code getPayload().getClass()}, but allows implementations to optimize by using lazy
     * loading or deserialization.
     *
     * @return The type of payload.
     */
    Class<?> payloadType();

    /**
     * Returns the {@link Metadata} for this {@code Message}.
     * <p>
     * The {@code Metadata} is a collection of key-value pairs, where both the key and values are {@link String}s.
     *
     * @return The {@link Metadata} for this {@code Message}.
     */
    Metadata metadata();

    /**
     * Returns a copy of this {@code Message} (implementation) with the given {@code metadata}.
     * <p>
     * All other fields, like for example the {@link #payload()}, remain unchanged.
     * <p>
     * While the implementation returned may be different from the implementation of {@code this}, implementations must
     * take special care in returning the same type of {@code Message} to prevent errors further downstream.
     *
     * @param metadata The new metadata for the {@code Message}.
     * @return A copy of {@code this Message (implementation)} with the given {@code metadata}.
     */
    Message withMetadata(Map<String, String> metadata);

    /**
     * Returns a copy of this {@code Message} (implementation) with its {@link Message#metadata() metadata} merged with
     * the given {@code metadata}.
     * <p>
     * All other fields, like for example the {@link #payload()}, remain unchanged.
     *
     * @param metadata The metadata to merge with.
     * @return A copy of {@code this Message (implementation)} with the given {@code metadata}.
     */
    Message andMetadata(Map<String, @Nullable String> metadata);

    /**
     * Returns a <b>new</b> {@code Message} implementation with its {@link #payload()} converted to the given
     * {@code type} by the given {@code converter}. This new {@code Message} is effectively a copy of
     * {@code this Message} with a renewed payload and {@link #payloadType()}.
     * <p>
     * Will return the {@code this} instance if the {@link #payloadType() payload type} is
     * {@link Class#isAssignableFrom(Class) assignable from} the converted result.
     *
     * @param type      The type to convert the {@link #payload()} to.
     * @param converter The converter to convert the {@link #payload()} with.
     * @return A <b>new</b> {@code Message} implementation with its {@link #payload()} converted to the given
     * {@code type} by the given {@code converter}.
     * @throws ConversionException When {@link Converter#convert(Object, Class) conversion} is mandatory but no
     *                             {@code converter} is given.
     */
        default Message withConvertedPayload(Class<?> type, Converter converter) {
        return withConvertedPayload((Type) type, converter);
    }

    /**
     * Returns a <b>new</b> {@code Message} implementation with its {@link #payload()} converted to the given
     * {@code type} by the given {@code converter}. This new {@code Message} is effectively a copy of
     * {@code this Message} with a renewed payload and {@link #payloadType()}.
     * <p>
     * Will return the {@code this} instance if the {@link #payloadType() payload type} is
     * {@link Class#isAssignableFrom(Class) assignable from} the converted result.
     *
     * @param type      The type to convert the {@link #payload()} to.
     * @param converter The converter to convert the {@link #payload()} with.
     * @return A <b>new</b> {@code Message} implementation with its {@link #payload()} converted to the given
     * {@code type} by the given {@code converter}.
     * @throws ConversionException When {@link Converter#convert(Object, Class) conversion} is mandatory but no
     *                             {@code converter} is given.
     */
        default Message withConvertedPayload(TypeReference<?> type, Converter converter) {
        return withConvertedPayload(type.getType(), converter);
    }

    /**
     * Returns a <b>new</b> {@code Message} implementation with its {@link #payload()} converted to the given
     * {@code type} by the given {@code converter}. This new {@code Message} is effectively a copy of
     * {@code this Message} with a renewed payload and {@link #payloadType()}.
     * <p>
     * Will return the {@code this} instance if the {@link #payloadType() payload type} is
     * {@link Class#isAssignableFrom(Class) assignable from} the converted result.
     *
     * @param type      The type to convert the {@link #payload()} to.
     * @param converter The converter to convert the {@link #payload()} with.
     * @return A <b>new</b> {@code Message} implementation with its {@link #payload()} converted to the given
     * {@code type} by the given {@code converter}.
     * @throws ConversionException When {@link Converter#convert(Object, Class) conversion} is mandatory but no
     *                             {@code converter} is given.
     */
    Message withConvertedPayload(Type type, Converter converter);
}