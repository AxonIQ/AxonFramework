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

import org.axonframework.common.IdentifierFactory;
import org.axonframework.common.ObjectUtils;
import org.axonframework.common.TypeReference;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Generic implementation of the {@link Message} interface containing the {@link #payload() payload} and
 * {@link #metadata() metadata} in deserialized form.
 * <p>
 * This {@code Message} implementation is "conversion aware," as it is capable of maintaining a converter suitable for
 * its payload set via {@link #withConverter(Converter)} and maintains <b>any</b> conversion results from
 * {@link #payloadAs(Type, Converter)} and {@link #withConvertedPayload(Type, Converter)} (either invoked with a
 * {@link Class}, {@link TypeReference}, or {@link Type}), together with the hash of the given {@link Converter}. In
 * doing so, this {@code Message} optimizes subsequent {@code payloadAs/withConvertedPayload} invocations for the same
 * type-and-converter combination. If this optimization should be disabled, the {@code "AXON_CONVERSION_CACHE_ENABLED"}
 * system property can be set to {@code false}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 2.0.0
 */
public class GenericMessage extends AbstractMessage {

    private final @Nullable Object payload;
    private final Class<?> payloadType;
    private final Metadata metadata;
    private final @Nullable Converter converter;

    private final ConversionCache convertedPayloads;

    /**
     * Constructs a {@code GenericMessage} for the given {@code type} and {@code payload}.
     *
     * @param type    The {@link MessageType type} for this {@link Message}.
     * @param payload The payload for this {@link Message}.
     */
    public GenericMessage(MessageType type,
                          @Nullable Object payload) {
        this(type, payload, Metadata.emptyInstance());
    }

    /**
     * Constructs a {@code GenericMessage} for the given {@code type}, {@code payload}, and {@code metadata}.
     * <p>
     * In case the {@code payload == null}, {@link Void} will be used as the {@code payloadType}.
     *
     * @param type     The {@link MessageType type} for this {@link Message}.
     * @param payload  The payload for this {@link Message}.
     * @param metadata The metadata for this {@link Message}.
     */
    public GenericMessage(MessageType type,
                          @Nullable Object payload,
                          Map<String, String> metadata) {
        this(type, payload, getDeclaredPayloadType(payload), metadata);
    }

    /**
     * Constructs a {@code GenericMessage} for the given {@code type}, {@code payload}, {@code declaredPayloadType}, and
     * {@code metadata}.
     *
     * @param <P>                 The generic type of the expected payload of the resulting object.
     * @param type                The {@link MessageType type} for this {@link Message}.
     * @param payload             The payload of type {@code P} for this {@link Message}.
     * @param declaredPayloadType The declared type of the {@code payload} of this {@link Message}.
     * @param metadata            The metadata for this {@link Message}.
     */
    public <P> GenericMessage(MessageType type,
                              @Nullable P payload,
                              Class<P> declaredPayloadType,
                              Map<String, String> metadata) {
        this(IdentifierFactory.getInstance().generateIdentifier(), type, payload, declaredPayloadType, metadata);
    }

    /**
     * Constructs a {@code GenericMessage} for the given {@code identifier}, {@code type}, {@code payload}, and
     * {@code metadata}, intended to reconstruct another {@link Message}.
     *
     * @param identifier The identifier of this {@link Message}.
     * @param type       The {@link MessageType type} for this {@link Message}.
     * @param payload    The payload for this {@link Message}.
     * @param metadata   The metadata for this {@link Message}.
     */
    public GenericMessage(String identifier,
                          MessageType type,
                          @Nullable Object payload,
                          Map<String, String> metadata) {
        this(identifier, type, payload, getDeclaredPayloadType(payload), metadata);
    }

    /**
     * Constructs a {@code GenericMessage} for the given {@code identifier}, {@code type}, {@code payload}, and
     * {@code metadata}, intended to reconstruct another {@link Message}.
     *
     * @param <P>                 The generic type of the expected payload of the resulting object.
     * @param identifier          The identifier of this {@link Message}.
     * @param type                The {@link MessageType type} for this {@link Message}.
     * @param payload             The payload of type {@code P} for this {@link Message}.
     * @param declaredPayloadType The declared type of the {@code payload} of this {@link Message}.
     * @param metadata            The metadata for this {@link Message}.
     */
    public <P> GenericMessage(String identifier,
                              MessageType type,
                              @Nullable P payload,
                              Class<P> declaredPayloadType,
                              Map<String, String> metadata) {
        super(identifier, type);
        this.payload = payload;
        this.payloadType = declaredPayloadType;
        this.metadata = Metadata.from(metadata);
        this.convertedPayloads = new ConversionCache(payload);
        this.converter = null;
    }

    private GenericMessage(GenericMessage original,
                           Metadata metadata, @Nullable Converter converter) {
        super(original.identifier(), original.type());
        this.payload = original.payload();
        this.payloadType = original.payloadType();
        this.metadata = metadata;
        this.convertedPayloads = new ConversionCache(payload);
        this.converter = converter;
    }

    /**
     * Extract the {@link Class} of the provided {@code payload}. If {@code payload == null} this function returns
     * {@link Void} as the payload type.
     *
     * @param payload the payload of this {@link Message}
     * @return the declared type of the given {@code payload} or {@link Void} if {@code payload == null}
     */
    private static <T> Class<T> getDeclaredPayloadType(@Nullable T payload) {
        return ObjectUtils.nullSafeTypeOf(payload);
    }

    /**
     * Construct an empty message.
     *
     * @return A message with {@code null} {@link Message#payload()}, no {@link Metadata}, and a {@link Message#type()}
     * of {@code "empty"}.
     */
    public static Message emptyMessage() {
        return new GenericMessage(new MessageType("empty"), null);
    }

    @Override
    @Nullable
    public Object payload() {
        return this.payload;
    }

    @Override
    public @Nullable <T> T payloadAs(Class<T> type) {
        return payloadAs(type, this.converter);
    }

    @Override
    public @Nullable <T> T payloadAs(TypeReference<T> type) {
        return payloadAs(type.getType(), this.converter);
    }

    @Override
    @Nullable
    public <T> T payloadAs(Type type, @Nullable Converter converter) {
        //noinspection rawtypes,unchecked
        if (type instanceof Class clazz && clazz.isAssignableFrom(payloadType()) || Void.class.equals(payloadType())) {
            //noinspection unchecked
            return (T) payload();
        }

        if (converter == null) {
            throw new ConversionException("Cannot convert " + payloadType() + " to " + type + " without a converter.");
        }
        return convertedPayloads.convertIfAbsent(type, converter);
    }

    @Override
    public Class<?> payloadType() {
        return this.payloadType;
    }

    @Override
    public Metadata metadata() {
        return this.metadata;
    }

    @Override
    protected Message withMetadata(Metadata metadata) {
        return new GenericMessage(this, metadata, this.converter);
    }

    /**
     * Returns a new {@code GenericMessage} instance with the same payload and properties as this message and the given
     * {@code converter} to be used for payload conversion.
     * <p>
     * Note: if called from a subtype, the message will lose subtype information because this method creates a new
     * instance of {@code GenericMessage}.
     *
     * @param converter the converter for the new message
     * @return a copy of this instance with the converter
     */
    public GenericMessage withConverter(@Nullable Converter converter) {
        return new GenericMessage(this, this.metadata, converter);
    }

    @Override
    public Message withConvertedPayload(Type type,
                                        Converter converter) {
        Object convertedPayload = payloadAs(type, converter);

        return ObjectUtils.nullSafeTypeOf(convertedPayload).isAssignableFrom(payloadType())
                ? (Message) this
                : new GenericMessage(identifier(), type(), convertedPayload, metadata());
    }
}
