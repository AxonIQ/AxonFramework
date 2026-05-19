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
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generic implementation of the {@link DomainEventMessage} interface.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 2.0.0
 */
public class GenericDomainEventMessage extends GenericEventMessage implements DomainEventMessage {

    private final String aggregateType;
    private final String aggregateIdentifier;
    private final long sequenceNumber;

    /**
     * Constructs a {@code GenericDomainEventMessage} originating from an aggregate of the given {@code aggregateType}
     * with the given {@code aggregateIdentifier}, {@code sequenceNumber}, {@code type}, and {@code payload}.
     * <p>
     * The {@link Metadata} defaults to an empty instance.
     *
     * @param aggregateType       The domain type generating this {@link DomainEventMessage}.
     * @param aggregateIdentifier The identifier of the aggregate generating this {@link DomainEventMessage}.
     * @param sequenceNumber      The {@link DomainEventMessage DomainEventMessage's} sequence number.
     * @param type                The {@link MessageType type} for this {@link DomainEventMessage}.
     * @param payload             The payload for this {@link DomainEventMessage}.
     */
    public GenericDomainEventMessage(String aggregateType,
                                     String aggregateIdentifier,
                                     long sequenceNumber,
                                     MessageType type,
                                     Object payload) {
        this(aggregateType, aggregateIdentifier, sequenceNumber, type, payload, Metadata.emptyInstance());
    }

    /**
     * Constructs a {@code GenericDomainEventMessage} originating from an aggregate of the given {@code aggregateType}
     * with the given {@code aggregateIdentifier}, {@code sequenceNumber}, {@code type}, {@code payload}, and
     * {@code metadata}.
     *
     * @param aggregateType       The domain type generating this {@link DomainEventMessage}.
     * @param aggregateIdentifier The identifier of the aggregate generating this {@link DomainEventMessage}.
     * @param sequenceNumber      The {@link DomainEventMessage DomainEventMessage's} sequence number.
     * @param type                The {@link MessageType type} for this {@link DomainEventMessage}.
     * @param payload             The payload for this {@link DomainEventMessage}.
     * @param metadata            The metadata for this {@link DomainEventMessage}.
     */
    public GenericDomainEventMessage(String aggregateType,
                                     String aggregateIdentifier,
                                     long sequenceNumber,
                                     MessageType type,
                                     Object payload,
                                     Map<String, String> metadata) {
        this(aggregateType,
             aggregateIdentifier,
             sequenceNumber,
             new GenericMessage(type, payload, metadata),
             ClockUtils.instant());
    }

    /**
     * Constructs a {@code GenericDomainEventMessage} originating from an aggregate of the given {@code aggregateType}
     * with the given {@code aggregateIdentifier}, {@code sequenceNumber}, {@code messageIdentifier}, {@code type},
     * {@code payload}, {@code metadata}, and {@code timestamp}.
     *
     * @param aggregateType       The domain type generating this {@link DomainEventMessage}.
     * @param aggregateIdentifier The identifier of the aggregate generating this {@link DomainEventMessage}.
     * @param sequenceNumber      The {@link DomainEventMessage DomainEventMessage's} sequence number.
     * @param messageIdentifier   The identifier of this {@link DomainEventMessage}.
     * @param type                The {@link MessageType type} for this {@link DomainEventMessage}.
     * @param payload             The payload for this {@link DomainEventMessage}.
     * @param metadata            The metadata for this {@link DomainEventMessage}.
     * @param timestamp           The {@link Instant timestamp} of this {@link DomainEventMessage DomainEventMessage's}
     *                            creation.
     */
    public GenericDomainEventMessage(String aggregateType,
                                     String aggregateIdentifier,
                                     long sequenceNumber,
                                     String messageIdentifier,
                                     MessageType type,
                                     Object payload,
                                     Map<String, String> metadata,
                                     Instant timestamp) {
        this(aggregateType,
             aggregateIdentifier,
             sequenceNumber,
             new GenericMessage(messageIdentifier, type, payload, metadata),
             timestamp);
    }

    /**
     * Constructs a {@code GenericDomainEventMessage} originating from an aggregate of the given {@code aggregateType}
     * with the given {@code aggregateIdentifier}, {@code sequenceNumber}, {@code delegate}, and
     * {@code timestampSupplier}, intended to reconstruct another {@link DomainEventMessage}.
     * <p>
     * The timestamp of the event is supplied lazily through the given {@code timestampSupplier} to prevent unnecessary
     * deserialization of the timestamp.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param aggregateType       The domain type generating this {@link DomainEventMessage}.
     * @param aggregateIdentifier The identifier of the aggregate generating this {@link DomainEventMessage}.
     * @param sequenceNumber      The {@link DomainEventMessage DomainEventMessage's} sequence number.
     * @param delegate            The {@link Message} containing {@link Message#payload() payload},
     *                            {@link Message#type() type}, {@link Message#identifier() identifier} and
     *                            {@link Message#metadata() metadata} for the {@link DomainEventMessage} to
     *                            reconstruct.
     * @param timestampSupplier   The {@link Instant timestampSupplier} of this
     *                            {@link DomainEventMessage GenericDomainEventMessage's} creation.
     */
    public GenericDomainEventMessage(String aggregateType,
                                     String aggregateIdentifier,
                                     long sequenceNumber,
                                     Message delegate,
                                     Supplier<Instant> timestampSupplier) {
        super(delegate, timestampSupplier);
        this.aggregateType = aggregateType;
        this.aggregateIdentifier = aggregateIdentifier;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Constructs a {@code GenericDomainEventMessage} originating from an aggregate of the given {@code aggregateType}
     * with the given {@code aggregateIdentifier}, {@code sequenceNumber}, {@code delegate}, and {@code timestamp},
     * intended to reconstruct another {@link DomainEventMessage}.
     * <p>
     * The {@code delegate} will be used supply the {@link Message#payload() payload}, {@link Message#type() type},
     * {@link Message#metadata() metadata} and {@link Message#identifier() identifier} of the resulting
     * {@code GenericEventMessage}.
     * <p>
     * Unlike the other constructors, this constructor will not attempt to retrieve any correlation data from the Unit
     * of Work.
     *
     * @param aggregateType       The domain type generating this {@link DomainEventMessage}.
     * @param aggregateIdentifier The identifier of the aggregate generating this {@link DomainEventMessage}.
     * @param sequenceNumber      The {@link DomainEventMessage DomainEventMessage's} sequence number.
     * @param delegate            The {@link Message} containing {@link Message#payload() payload},
     *                            {@link Message#type() type}, {@link Message#identifier() identifier} and
     *                            {@link Message#metadata() metadata} for the {@link DomainEventMessage} to
     *                            reconstruct.
     * @param timestamp           The {@link Instant timestamp} of this {@link DomainEventMessage DomainEventMessage's}
     *                            creation.
     */
    public GenericDomainEventMessage(String aggregateType,
                                     String aggregateIdentifier,
                                     long sequenceNumber,
                                     Message delegate,
                                     Instant timestamp) {
        super(delegate, timestamp);
        this.aggregateType = aggregateType;
        this.aggregateIdentifier = aggregateIdentifier;
        this.sequenceNumber = sequenceNumber;
    }

    @Override
    public String getType() {
        return aggregateType;
    }

    @Override
    public String getAggregateIdentifier() {
        return aggregateIdentifier;
    }

    @Override
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    @Override
        public GenericDomainEventMessage withMetadata(Map<String, String> metadata) {
        if (metadata().equals(metadata)) {
            return this;
        }
        return new GenericDomainEventMessage(aggregateType,
                                             aggregateIdentifier,
                                             sequenceNumber,
                                             delegate().withMetadata(metadata),
                                             timestamp());
    }

    @Override
        public GenericDomainEventMessage andMetadata(Map<String, String> metadata) {
        //noinspection ConstantConditions
        if (metadata == null || metadata.isEmpty() || metadata().equals(metadata)) {
            return this;
        }
        return new GenericDomainEventMessage(aggregateType,
                                             aggregateIdentifier,
                                             sequenceNumber,
                                             delegate().andMetadata(metadata),
                                             timestamp());
    }

    @Override
    protected void describeTo(StringBuilder stringBuilder) {
        super.describeTo(stringBuilder);
        stringBuilder.append('\'').append(", aggregateType='")
                     .append(getType()).append('\'')
                     .append(", aggregateIdentifier='")
                     .append(getAggregateIdentifier()).append('\'')
                     .append(", sequenceNumber=")
                     .append(getSequenceNumber());
    }

    @Override
    protected String describeType() {
        return "GenericDomainEventMessage";
    }
}
