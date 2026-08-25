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

package org.axonframework.conversion;

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.core.AbstractMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * A message containing serialized {@link #payload() payload data} and {@link #metadata() metadata}.
 * <p>
 * A {@link SerializedMessage} will deserialize the payload or metadata on demand when {@link #payload()} or
 * {@link #metadata()} is called.
 * <p>
 * The {@code SerializedMessage} guarantees that the payload and metadata will not be deserialized more than once.
 * Messages of this type  will not be serialized more than once by the same serializer.
 *
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 3.0.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class SerializedMessage<P> extends AbstractMessage {

    private final LazyDeserializingObject<?> payload;
    private final LazyDeserializingObject<Metadata> metadata;

    /**
     * Constructs a {@code SerializedMessage} with given {@code identifier} from the given {@code serializedPayload} and
     * {@code serializedMetadata}.
     * <p>
     * The given {@code serializer} is used to deserialize the data.
     *
     * @param identifier         The identifier of this {@code SerializedMessage}.
     * @param serializedPayload  The {@link SerializedObject serializer} message payload.
     * @param serializedMetadata The {@link SerializedObject serializer} message metadata.
     * @param serializer         The {@link Serializer} required when the data needs to be deserialized.
     */
    public SerializedMessage(String identifier,
                             SerializedObject<?> serializedPayload,
                             SerializedObject<?> serializedMetadata,
                             Serializer serializer) {
        // TODO #3012 - I think the Serializer/Converter should provide the MessageType in this case.
        this(identifier,
             new MessageType(serializedPayload.getType().getName()),
             new LazyDeserializingObject<>(serializedPayload, serializer),
             new LazyDeserializingObject<>(serializedMetadata, serializer));
    }

    /**
     * Constructs a {@code SerializedMessage} with given {@code identifier}, {@code type}, and lazily deserialized
     * {@code payload} and {@code metadata}.
     * <p>
     * The {@code identifier} originates from the {@link Message} where the lazily
     * deserialized {@code payload} and {@code metadata} originate from.
     *
     * @param identifier The identifier of this {@code SerializedMessage}.
     * @param type       The {@link MessageType type} for this {@code SerializedMessage}.
     * @param payload    serialized payload that can be deserialized on demand and never more than once
     * @param metadata   serialized metadata that can be deserialized on demand and never more than once
     */
    public SerializedMessage(String identifier,
                             MessageType type,
                             LazyDeserializingObject<?> payload,
                             LazyDeserializingObject<Metadata> metadata) {
        super(identifier, type);
        this.metadata = metadata;
        this.payload = payload;
    }

    private SerializedMessage(SerializedMessage message,
                              LazyDeserializingObject<Metadata> newMetadata) {
        this(message.identifier(), message.type(), message.payload, newMetadata);
    }

    @Override
    @Nullable
    public Object payload() {
        try {
            return payload.getObject();
        } catch (SerializationException e) {
            throw new SerializationException("Error while deserializing payload of message " + identifier(), e);
        }
    }

    @Override
    @Nullable
    public <T> T payloadAs(Type type, @Nullable Converter converter) {
        // This class will be removed/replaced by the ConversionAwareMessage, so skipping implementation
        return null;
    }

    @Override
        public Metadata metadata() {
        try {
            return metadata.getObject();
        } catch (SerializationException e) {
            throw new SerializationException("Error while deserializing metadata of message " + identifier(), e);
        }
    }

    @Override
    public Class<?> payloadType() {
        return payload.getType();
    }

    @Override
    protected SerializedMessage withMetadata(Metadata metadata) {
        if (metadata().equals(metadata)) {
            return this;
        }
        return new SerializedMessage(this, new LazyDeserializingObject<>(metadata));
    }

    @Override
    public SerializedMessage withMetadata(Map<String, String> metadata) {
        return (SerializedMessage) super.withMetadata(metadata);
    }

    @Override
    public SerializedMessage andMetadata(Map<String, String> metadata) {
        return (SerializedMessage) super.andMetadata(metadata);
    }

    @Override
    public Message withConvertedPayload(Type type, Converter converter) {
        // This class will be removed/replaced by the ConversionAwareMessage, so skipping implementation
        return null;
    }

    /**
     * Indicates whether the payload of this message has already been deserialized.
     *
     * @return {@code true} if the payload is deserialized, otherwise {@code false}
     */
    public boolean isPayloadDeserialized() {
        return payload.isDeserialized();
    }

    /**
     * Indicates whether the metadata of this message has already been deserialized.
     *
     * @return {@code true} if the metadata is deserialized, otherwise {@code false}
     */
    public boolean isMetadataDeserialized() {
        return metadata.isDeserialized();
    }
}
