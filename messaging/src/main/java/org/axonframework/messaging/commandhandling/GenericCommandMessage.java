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

import org.axonframework.common.ObjectUtils;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDecorator;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Generic implementation of the {@link CommandMessage} interface.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 2.0.0
 */
public class GenericCommandMessage extends MessageDecorator implements CommandMessage {

    private final String routingKey;
    private final Integer priority;

    /**
     * Constructs a {@code GenericCommandMessage} for the given {@code type} and {@code payload}.
     * <p>
     * The {@link Metadata} defaults to an empty instance.
     *
     * @param type    The {@link MessageType type} for this {@link CommandMessage}.
     * @param payload The payload for this {@link CommandMessage}.
     */
    public GenericCommandMessage(MessageType type,
                                 @Nullable Object payload) {
        this(type, payload, Metadata.emptyInstance());
    }

    /**
     * Constructs a {@code GenericCommandMessage} for the given {@code type}, {@code payload}, and {@code metadata}.
     *
     * @param type     The {@link MessageType type} for this {@link CommandMessage}.
     * @param payload  The payload for this {@link CommandMessage}.
     * @param metadata The metadata for this {@link CommandMessage}.
     */
    public GenericCommandMessage(MessageType type,
                                 @Nullable Object payload,
                                 Map<String, String> metadata) {
        this(new GenericMessage(type, payload, metadata));
    }


    /**
     * Constructs a {@code GenericCommandMessage} for the given {@code type}, {@code payload}, and {@code metadata}.
     * <p>
     * Optionally, a {@code routingKey} and/or a {@code priority} may be passed.
     *
     * @param type       The {@link MessageType type} for this {@link CommandMessage}.
     * @param payload    The payload for this {@link CommandMessage}.
     * @param metadata   The metadata for this {@link CommandMessage}.
     * @param routingKey The routing key for this {@link CommandMessage}, if any.
     * @param priority   The priority for this {@link CommandMessage}, if any.
     */
    public GenericCommandMessage(MessageType type,
                                 Object payload,
                                 Map<String, String> metadata,
                                 @Nullable String routingKey,
                                 @Nullable Integer priority) {
        this(new GenericMessage(type, payload, metadata), routingKey, priority);
    }

    /**
     * Constructs a {@code GenericCommandMessage} with given {@code delegate}.
     * <p>
     * The {@code delegate} will be used supply the {@link Message#payload() payload}, {@link Message#type() type},
     * {@link Message#metadata() metadata} and {@link Message#identifier() identifier} of the resulting
     * {@code GenericCommandMessage}.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param delegate The {@link Message} containing {@link Message#payload() payload},
     *                 {@link Message#type() qualifiedName}, {@link Message#identifier() identifier} and
     *                 {@link Message#metadata() metadata} for the {@link CommandMessage} to reconstruct.
     */
    public GenericCommandMessage(Message delegate) {
        this(delegate, null, null);
    }

    /**
     * Constructs a {@code GenericCommandMessage} with given {@code delegate}, {@code routingKey}, and
     * {@code priority}.
     * <p>
     * The {@code delegate} will be used supply the {@link Message#payload() payload}, {@link Message#type() type},
     * {@link Message#metadata() metadata} and {@link Message#identifier() identifier} of the resulting
     * {@code GenericCommandMessage}.<br/> Optionally, a {@code routingKey} and/or a {@code priority} may be passed.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param delegate   The {@link Message} containing {@link Message#payload() payload},
     *                   {@link Message#type() qualifiedName}, {@link Message#identifier() identifier} and
     *                   {@link Message#metadata() metadata} for the {@link CommandMessage} to reconstruct.
     * @param routingKey The routing key for this {@link CommandMessage}, if any.
     * @param priority   The priority for this {@link CommandMessage}, if any.
     */
    public GenericCommandMessage(Message delegate,
                                 @Nullable String routingKey,
                                 @Nullable Integer priority) {
        super(delegate);
        this.routingKey = routingKey;
        this.priority = priority;
    }

    @Override
    public Optional<String> routingKey() {
        return Optional.ofNullable(routingKey);
    }

    @Override
    public OptionalInt priority() {
        return priority != null ? OptionalInt.of(priority) : OptionalInt.empty();
    }

    @Override
    public CommandMessage withMetadata(Map<String, String> metadata) {
        return new GenericCommandMessage(delegate().withMetadata(metadata), routingKey, priority);
    }

    @Override
    public CommandMessage andMetadata(Map<String, String> metadata) {
        return new GenericCommandMessage(delegate().andMetadata(metadata), routingKey, priority);
    }

    @Override
        public CommandMessage withConvertedPayload(Type type, Converter converter) {
        Object convertedPayload = payloadAs(type, converter);
        if (ObjectUtils.nullSafeTypeOf(convertedPayload).isAssignableFrom(payloadType())) {
            return this;
        }
        Message delegate = delegate();
        Message converted = new GenericMessage(delegate.identifier(),
                                               delegate.type(),
                                               convertedPayload,
                                               delegate.metadata());
        return new GenericCommandMessage(converted, routingKey, priority);
    }

    /**
     * Returns a new {@code GenericCommandMessage} with the same properties as this message and the given
     * {@code converter} set for use in {@link #payloadAs(Class)}.
     * <p>
     * Note: if called from a subtype, the message will lose subtype information because this method creates a new
     * instance of {@code GenericCommandMessage}.
     *
     * @param converter the converter for the new message
     * @return a copy of this instance with the converter set
     */
    public GenericCommandMessage withConverter(@Nullable Converter converter) {
        Message updated = delegate() instanceof GenericMessage gm
                ? gm.withConverter(converter)
                : delegate();
        return new GenericCommandMessage(updated, routingKey, priority);
    }

    @Override
    protected void describeTo(StringBuilder stringBuilder) {
        super.describeTo(stringBuilder);
        stringBuilder.append(", routingKey='")
                     .append(routingKey().orElse("null"))
                     .append("', priority='")
                     .append(priority().orElse(0))
                     .append("'");
    }

    @Override
    protected String describeType() {
        return "GenericCommandMessage";
    }
}