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

package org.axonframework.deadline;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.ObjectUtils;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.conversion.Converter;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generic implementation of the {@link DeadlineMessage} interface.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3.0
 */
public class GenericDeadlineMessage extends GenericEventMessage implements DeadlineMessage {

    private final String deadlineName;

    /**
     * Constructs a {@code GenericDeadlineMessage} for the given {@code type} and {@code deadlineName}.
     * <p>
     * The {@link #payload()} defaults to {@code null} and the {@link Metadata} defaults to an empty instance.
     *
     * @param type         The {@link MessageType type} for this {@link DeadlineMessage}.
     * @param deadlineName The type for this {@link DeadlineMessage}.
     */
    public GenericDeadlineMessage(MessageType type,
                                  String deadlineName) {
        this(deadlineName, type, null);
    }

    /**
     * Constructs a {@code GenericDeadlineMessage} for the given {@code deadlineName}, {@code type}, and
     * {@code payload}.
     * <p>
     * The {@link Metadata} defaults to an empty instance.
     *
     * @param deadlineName The type for this {@link DeadlineMessage}.
     * @param type         The {@link MessageType type} for this {@link DeadlineMessage}.
     * @param payload      The payload for this {@link DeadlineMessage}.
     */
    public GenericDeadlineMessage(String deadlineName,
                                  MessageType type,
                                  @Nullable Object payload) {
        this(deadlineName, type, payload, Metadata.emptyInstance());
    }

    /**
     * Constructs a {@code GenericDeadlineMessage} for the given {@code deadlineName}, {@code type}, {@code payload},
     * and {@code metadata}.
     *
     * @param deadlineName The name for this {@link DeadlineMessage}.
     * @param type         The {@link MessageType type} for this {@link DeadlineMessage}.
     * @param payload      The payload for this {@link DeadlineMessage}.
     * @param metadata     The metadata for this {@link DeadlineMessage}.
     */
    public GenericDeadlineMessage(String deadlineName,
                                  MessageType type,
                                  @Nullable Object payload,
                                  Map<String, String> metadata) {
        super(type, payload, metadata);
        this.deadlineName = deadlineName;
    }

    /**
     * Constructs a {@code GenericDeadlineMessage} for the given {@code deadlineName}, {@code identifier}, {@code type},
     * {@code payload}, {@code metadata}, and {@code timestamp}.
     *
     * @param deadlineName The name for this {@link DeadlineMessage}.
     * @param identifier   The identifier of this {@link DeadlineMessage}.
     * @param type         The {@link MessageType type} for this {@link DeadlineMessage}.
     * @param payload      The payloadfor this {@link DeadlineMessage}.
     * @param metadata     The metadata for this {@link DeadlineMessage}.
     * @param timestamp    The {@link Instant timestamp} of this {@link DeadlineMessage DeadlineMessage's} creation.
     */
    public GenericDeadlineMessage(String deadlineName,
                                  String identifier,
                                  MessageType type,
                                  @Nullable Object payload,
                                  Map<String, String> metadata,
                                  Instant timestamp) {
        super(identifier, type, payload, metadata, timestamp);
        this.deadlineName = deadlineName;
    }

    /**
     * Constructs a {@code GenericDeadlineMessage} for the given {@code deadlineName}, {@code delegate} and
     * {@code timestampSupplier}, intended to reconstruct another {@link DeadlineMessage}.
     * <p>
     * The timestamp of the deadline is supplied lazily through the given {@code timestampSupplier} to prevent
     * unnecessary deserialization of the timestamp.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param deadlineName      The name for this {@link DeadlineMessage}.
     * @param delegate          The {@link Message} containing {@link Message#payload() payload},
     *                          {@link Message#type() type}, {@link Message#identifier() identifier} and
     *                          {@link Message#metadata() metadata} for the {@link DeadlineMessage} to reconstruct.
     * @param timestampSupplier {@link Supplier} for the {@link Instant timestamp} of the
     *                          {@link DeadlineMessage DeadlineMessage's} creation.
     */
    public GenericDeadlineMessage(String deadlineName,
                                  Message delegate,
                                  Supplier<Instant> timestampSupplier) {
        super(delegate, timestampSupplier);
        this.deadlineName = deadlineName;
    }

    @Override
        public String getDeadlineName() {
        return deadlineName;
    }

    @Override
        public DeadlineMessage withMetadata(Map<String, String> metadata) {
        return new GenericDeadlineMessage(deadlineName, delegate().withMetadata(metadata), this::timestamp);
    }

    @Override
        public DeadlineMessage andMetadata(Map<String, String> additionalMetadata) {
        return new GenericDeadlineMessage(
                deadlineName, delegate().andMetadata(additionalMetadata), this::timestamp
        );
    }

    @Override
        public DeadlineMessage withConvertedPayload(Type type, Converter converter) {
        Object convertedPayload = payloadAs(type, converter);
        if (ObjectUtils.nullSafeTypeOf(convertedPayload).isAssignableFrom(payloadType())) {
            return this;
        }
        Message delegate = delegate();
        Message converted = new GenericMessage(delegate.identifier(),
                                                    delegate.type(),
                                                    convertedPayload,
                                                    delegate.metadata());
        return new GenericDeadlineMessage(getDeadlineName(), converted, this::timestamp);
    }

    @Override
    protected String describeType() {
        return "GenericDeadlineMessage";
    }
}
