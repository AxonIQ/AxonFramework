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

package org.axonframework.messaging.eventhandling;

import org.axonframework.common.ClockUtils;
import org.axonframework.common.ObjectUtils;
import org.axonframework.conversion.CachingSupplier;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDecorator;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generic implementation of the {@link EventMessage} interface.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 2.0.0
 */
public class GenericEventMessage extends MessageDecorator implements EventMessage {

    private final Supplier<Instant> timestampSupplier;

    /**
     * Constructs a {@code GenericEventMessage} for the given {@code type} and {@code payload}.
     * <p>
     * The {@link Metadata} defaults to an empty instance.
     *
     * @param type    The {@link MessageType type} for this {@link EventMessage}.
     * @param payload The payload for this {@link EventMessage}.
     */
    public GenericEventMessage(MessageType type,
                               @Nullable Object payload) {
        this(type, payload, Metadata.emptyInstance());
    }

    /**
     * Constructs a {@code GenericEventMessage} for the given {@code type}, {@code payload} and {@code metadata}.
     *
     * @param type     The {@link MessageType type} for this {@link EventMessage}.
     * @param payload  The payload for this {@link EventMessage}.
     * @param metadata The metadata for this {@link EventMessage}.
     */
    public GenericEventMessage(MessageType type,
                               @Nullable Object payload,
                               Map<String, @Nullable String> metadata) {
        this(new GenericMessage(type, payload, metadata), ClockUtils.instant());
    }

    /**
     * Constructs a {@code GenericEventMessage} for the given {@code identifier}, {@code type}, {@code payload},
     * {@code metadata}, and {@code timestamp}.
     *
     * @param identifier The identifier of this {@link EventMessage}.
     * @param type       The {@link MessageType type} for this {@link EventMessage}.
     * @param payload    The payload for this {@link EventMessage}.
     * @param metadata   The metadata for this {@link EventMessage}.
     * @param timestamp  The {@link Instant timestamp} of this {@link EventMessage EventMessage's} creation.
     */
    public GenericEventMessage(String identifier,
                               MessageType type,
                               @Nullable Object payload,
                               Map<String, String> metadata,
                               Instant timestamp) {
        this(new GenericMessage(identifier, type, payload, metadata), timestamp);
    }

    /**
     * Constructs a {@code GenericEventMessage} for the given {@code delegate}, intended
     * to reconstruct another {@link EventMessage}.
     * <p>
     * The timestamp of the event is supplied lazily through the global {@link ClockUtils#SUPPLIER}.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param delegate          The {@link Message} containing {@link Message#payload() payload},
     *                          {@link Message#type() type}, {@link Message#identifier() identifier} and
     *                          {@link Message#metadata() metadata} for the {@link EventMessage} to reconstruct.
     */
    public GenericEventMessage(Message delegate) {
        this(delegate, ClockUtils.SUPPLIER);
    }

    /**
     * Constructs a {@code GenericEventMessage} for the given {@code delegate} and {@code timestampSupplier}, intended
     * to reconstruct another {@link EventMessage}.
     * <p>
     * The timestamp of the event is supplied lazily through the given {@code timestampSupplier} to prevent unnecessary
     * deserialization of the timestamp.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param delegate          The {@link Message} containing {@link Message#payload() payload},
     *                          {@link Message#type() type}, {@link Message#identifier() identifier} and
     *                          {@link Message#metadata() metadata} for the {@link EventMessage} to reconstruct.
     * @param timestampSupplier {@link Supplier} for the {@link Instant timestamp} of the
     *                          {@link EventMessage EventMessage's} creation.
     */
    public GenericEventMessage(Message delegate,
                               Supplier<Instant> timestampSupplier) {
        super(delegate);
        this.timestampSupplier = CachingSupplier.of(timestampSupplier);
    }

    /**
     * Constructs a {@code GenericEventMessage} with given {@code delegate} and {@code timestamp}.
     * <p>
     * The {@code delegate} will be used supply the {@link Message#payload() payload}, {@link Message#type() type},
     * {@link Message#metadata() metadata} and {@link Message#identifier() identifier} of the resulting
     * {@code GenericEventMessage}.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param delegate  The {@link Message} containing {@link Message#payload() payload}, {@link Message#type() type},
     *                  {@link Message#identifier() identifier} and {@link Message#metadata() metadata} for the
     *                  {@link EventMessage} to reconstruct.
     * @param timestamp The {@link Instant timestamp} of this {@link EventMessage GenericEventMessage's} creation.
     */
    protected GenericEventMessage(Message delegate,
                                  Instant timestamp) {
        this(delegate, CachingSupplier.of(timestamp));
    }

    @Override
    public Instant timestamp() {
        return timestampSupplier.get();
    }

    @Override
    public EventMessage withMetadata(Map<String, String> metadata) {
        if (metadata().equals(metadata)) {
            return this;
        }
        return new GenericEventMessage(delegate().withMetadata(metadata), timestampSupplier);
    }

    @Override
    public EventMessage andMetadata(Map<String, String> metadata) {
        //noinspection ConstantConditions
        if (metadata == null || metadata.isEmpty() || metadata().equals(metadata)) {
            return this;
        }
        return new GenericEventMessage(delegate().andMetadata(metadata), timestampSupplier);
    }

    @Override
        public EventMessage withConvertedPayload(Type type, Converter converter) {
        Object convertedPayload = payloadAs(type, converter);
        if (ObjectUtils.nullSafeTypeOf(convertedPayload).isAssignableFrom(payloadType())) {
            return this;
        }
        Message delegate = delegate();
        Message converted = new GenericMessage(delegate.identifier(),
                                               delegate.type(),
                                               convertedPayload,
                                               delegate.metadata());
        return new GenericEventMessage(converted, timestamp());
    }

    /**
     * Returns a new {@code GenericEventMessage} with the same properties as this message and the given
     * {@code converter} set for use in {@link #payloadAs(Class)}.
     * <p>
     * Note: if called from a subtype, the message will lose subtype information because this method creates a new
     * instance of {@code GenericEventMessage}.
     *
     * @param converter the converter for the new message
     * @return a copy of this instance with the converter set
     */
    public GenericEventMessage withConverter(@Nullable Converter converter) {
        Message updated = delegate() instanceof GenericMessage gm
                ? gm.withConverter(converter)
                : delegate();
        return new GenericEventMessage(updated, this.timestampSupplier);
    }

    @Override
    protected void describeTo(StringBuilder stringBuilder) {
        super.describeTo(stringBuilder);
        stringBuilder.append(", timestamp='")
                     .append(timestamp())
                     .append("'");
    }

    @Override
    protected String describeType() {
        return "GenericEventMessage";
    }
}
