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

package org.axonframework.deadline.dbscheduler;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

import static java.lang.String.format;

/**
 * Pojo that contains the needed information for a {@link com.github.kagkarlsson.scheduler.task.Task} handling a
 * deadline, will be serialized and deserialized using the configured {@link Serializer} on the
 * {@link com.github.kagkarlsson.scheduler.Scheduler}. This object is used with the
 * {@code DbSchedulerDeadlineManager#binaryTask()}.
 *
 * @author Gerard Klijs
 * @since 4.8.0
 */
@SuppressWarnings("Duplicates")
public class DbSchedulerBinaryDeadlineDetails implements Serializable {

    private String d;
    private String t;
    private byte[] s;
    private String sc;
    private byte[] p;
    private String pc;
    private String r;
    private byte[] m;

    DbSchedulerBinaryDeadlineDetails() {
        //no-args constructor needed for deserialization
    }

    /**
     * Creates a new {@link DbSchedulerBinaryDeadlineDetails} object, likely based on a {@link DeadlineMessage}.
     *
     * @param deadlineName         The {@link String} with the type of the deadline.
     * @param type                 The {@link Message#type()} of the deadline.
     * @param scopeDescriptor      The {@link String} which tells what the scope is of the deadline.
     * @param scopeDescriptorClass The {@link String} which tells what the class of the scope descriptor is.
     * @param payload              The {@link String} with the payload. This can be null.
     * @param payloadClass         The {@link String} which tells what the class of the scope payload is.
     * @param payloadRevision      The {@link String} which tells what the revision of the scope payload is.
     * @param metadata             The {@link String} containing the metadata about the deadline. This can be null.
     */
    @SuppressWarnings("squid:S107")
    public DbSchedulerBinaryDeadlineDetails(String deadlineName,
                                            String type,
                                            byte[] scopeDescriptor,
                                            String scopeDescriptorClass,
                                            byte @Nullable [] payload,
                                            @Nullable String payloadClass,
                                            @Nullable String payloadRevision,
                                            byte @Nullable [] metadata) {
        this.d = deadlineName;
        this.t = type;
        this.s = scopeDescriptor;
        this.sc = scopeDescriptorClass;
        this.p = payload;
        this.pc = payloadClass;
        this.r = payloadRevision;
        this.m = metadata;
    }

    /**
     * Create a new {@link DbSchedulerBinaryDeadlineDetails} object, using the supplied serializer where needed.
     *
     * @param deadlineName The {@link String} with the name of the deadline.
     * @param descriptor   The {@link ScopeDescriptor} which tells what the scope is of the deadline.
     * @param message      The {@link DeadlineMessage} containing the payload and metadata which needs to be
     *                     serialized.
     * @param serializer   The {@link Serializer} used to serialize the {@code descriptor}, {@code payload},
     *                     {@code metadata}, as well as the whole {@link DbSchedulerBinaryDeadlineDetails}.
     * @return The serialized {@link String} representation of the details.
     */
    static DbSchedulerBinaryDeadlineDetails serialized(String deadlineName,
                                                       ScopeDescriptor descriptor,
                                                       DeadlineMessage message,
                                                       Serializer serializer) {
        SerializedObject<byte[]> serializedDescriptor = serializer.serialize(descriptor, byte[].class);
        SerializedObject<byte[]> serializedPayload = serializer.serialize(message.payload(), byte[].class);
        SerializedObject<byte[]> serializedMetadata = serializer.serialize(message.metadata(), byte[].class);

        return new DbSchedulerBinaryDeadlineDetails(deadlineName,
                                                    message.type().toString(),
                                                    serializedDescriptor.getData(),
                                                    serializedDescriptor.getType().getName(),
                                                    serializedPayload.getData(),
                                                    serializedPayload.getType().getName(),
                                                    serializedPayload.getType().getRevision(),
                                                    serializedMetadata.getData());
    }

    /**
     * Returns the {@link String} with the name of the deadline.
     *
     * @return The {@link String} with the name of the deadline.
     */
    public String getD() {
        return d;
    }

    /**
     * Returns the {@link Message#type()} of this deadline.
     *
     * @return The {@link Message#type()} of this deadline.
     */
    public String getT() {
        return t;
    }

    /**
     * Returns the serialized {@code byte[]} which tells what the scope is of the deadline.
     *
     * @return The serialized {@code byte[]} which tells what the scope is of the deadline.
     */
    public byte[] getS() {
        return s;
    }

    /**
     * Returns the {@link String} with the class of the scope descriptor.
     *
     * @return The {@link String} with the class of the scope descriptor.
     */
    public String getSc() {
        return sc;
    }

    /**
     * Returns the serialized {@code byte[]} of the payload. This can be null.
     *
     * @return The serialized {@code byte[]} of the payload. This can be null.
     */
    public byte[] getP() {
        return p;
    }

    /**
     * Returns the {@link String} with the class of the payload. This can be null.
     *
     * @return The {@link String} with the class of the payload. This can be null.
     */
    public String getPc() {
        return pc;
    }

    /**
     * Returns the {@link String} with the revision of the payload. This can be null.
     *
     * @return The {@link String} with the revision of the payload. This can be null.
     */
    public String getR() {
        return r;
    }

    /**
     * Returns the {@code byte[]} containing the metadata about the deadline.
     *
     * @return The {@code byte[]} containing the metadata about the deadline.
     */
    public byte[] getM() {
        return m;
    }

    /**
     * Returns the {@link DbSchedulerBinaryDeadlineDetails} as an {@link GenericDeadlineMessage}, with the {@code}
     * timestamp set using the {@code GenericEventMessage.clock}.
     *
     * @return the {@link GenericDeadlineMessage} with all the properties of this pojo, and a timestamp.
     */
    public GenericDeadlineMessage asDeadLineMessage(Serializer serializer) {
        return new GenericDeadlineMessage(d,
                                          MessageType.fromString(t),
                                          getDeserializedPayload(serializer),
                                          getDeserializedMetadata(serializer));
    }

    private Object getDeserializedPayload(Serializer serializer) {
        SimpleSerializedObject<byte[]> serializedDeadlinePayload =
                new SimpleSerializedObject<>(p, byte[].class, pc, r);
        return serializer.deserialize(serializedDeadlinePayload);
    }

    private Metadata getDeserializedMetadata(Serializer serializer) {
        SimpleSerializedObject<byte[]> serializedDeadlineMetadata =
                new SimpleSerializedObject<>(m, byte[].class, Metadata.class.getName(), null);
        return serializer.deserialize(serializedDeadlineMetadata);
    }

    /**
     * Returns the serialized {@link ScopeDescriptor} using the supplied {@link Serializer}. This will be an instance of
     * the {@code scopeDescriptorClass} property.
     *
     * @return the {@link ScopeDescriptor} that is serialized using the supplied {@link Serializer}.
     */
    public ScopeDescriptor getDeserializedScopeDescriptor(Serializer serializer) {
        SimpleSerializedObject<byte[]> serializedDeadlineScope =
                new SimpleSerializedObject<>(s, byte[].class, sc, null);
        return serializer.deserialize(serializedDeadlineScope);
    }

    @Override
    public String toString() {
        return format("DbScheduler deadline details, deadlineName: [%s], " +
                              "type: [%s], " +
                              "scopeDescriptor: [%s], " +
                              "scopeDescriptorClass: [%s], " +
                              "payload: [%s], " +
                              "payloadClass: [%s], " +
                              "payloadRevision: [%s], " +
                              "metadata: [%s]",
                      d, t, Arrays.toString(s), sc, Arrays.toString(p), pc, r, Arrays.toString(m));
    }

    @Override
    public int hashCode() {
        return Objects.hash(d, t, Arrays.hashCode(s), sc, Arrays.hashCode(p), pc, r, Arrays.hashCode(m));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final DbSchedulerBinaryDeadlineDetails other = (DbSchedulerBinaryDeadlineDetails) obj;
        return Objects.equals(this.d, other.d) &&
                Objects.equals(this.t, other.t) &&
                Arrays.equals(this.s, other.s) &&
                Objects.equals(this.sc, other.sc) &&
                Arrays.equals(this.p, other.p) &&
                Objects.equals(this.pc, other.pc) &&
                Objects.equals(this.r, other.r) &&
                Arrays.equals(this.m, other.m);
    }
}
