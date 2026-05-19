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
import org.axonframework.common.IdentifierFactory;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.eventhandling.DomainEventMessage;
import org.axonframework.messaging.eventhandling.GenericDomainEventMessage;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Test utilities when dealing with domain events.
 *
 * @deprecated Since the {@link org.axonframework.messaging.eventhandling.DomainEventMessage} will be removed. Once that is gone, we can remove this class.
 */
@Deprecated
public abstract class DomainEventTestUtils {

    private static final MessageType TYPE = new MessageType("event");
    public static final String PAYLOAD = "payload";
    public static final String AGGREGATE = "aggregate";
    private static final String AGGREGATE_TYPE = "aggregateType";
    private static final Metadata METADATA = Metadata.emptyInstance();

    private DomainEventTestUtils() {
        // Utility class
    }

    public static List<org.axonframework.messaging.eventhandling.DomainEventMessage> createDomainEvents(int numberOfEvents) {
        return createDomainEvents(() -> AGGREGATE, numberOfEvents);
    }

    public static List<org.axonframework.messaging.eventhandling.DomainEventMessage> createDomainEvents(Supplier<String> aggregateId, int numberOfEvents) {
        return IntStream.range(0, numberOfEvents)
                        .mapToObj(sequenceNumber -> createDomainEvent(AGGREGATE_TYPE,
                                                                      IdentifierFactory.getInstance()
                                                                                       .generateIdentifier(),
                                                                      aggregateId.get(),
                                                                      sequenceNumber,
                                                                      PAYLOAD + sequenceNumber,
                                                                      METADATA))
                        .collect(Collectors.toList());
    }

    public static org.axonframework.messaging.eventhandling.DomainEventMessage createDomainEvent() {
        return createDomainEvent(0);
    }

    public static org.axonframework.messaging.eventhandling.DomainEventMessage createDomainEvent(long sequenceNumber) {
        return createDomainEvent(AGGREGATE, sequenceNumber);
    }

    public static org.axonframework.messaging.eventhandling.DomainEventMessage createDomainEvent(long sequenceNumber, Instant timestamp) {
        return new org.axonframework.messaging.eventhandling.GenericDomainEventMessage(
                AGGREGATE_TYPE, AGGREGATE, sequenceNumber,
                IdentifierFactory.getInstance().generateIdentifier(), TYPE,
                PAYLOAD, METADATA, timestamp
        );
    }

    public static org.axonframework.messaging.eventhandling.DomainEventMessage createDomainEvent(String aggregateId, long sequenceNumber) {
        return createDomainEvent(aggregateId, sequenceNumber, PAYLOAD);
    }

    public static org.axonframework.messaging.eventhandling.DomainEventMessage createDomainEvent(String aggregateId, long sequenceNumber,
                                                                                                 String payload) {
        return createDomainEvent(AGGREGATE_TYPE,
                                 IdentifierFactory.getInstance().generateIdentifier(),
                                 aggregateId,
                                 sequenceNumber,
                                 payload,
                                 METADATA);
    }

    public static org.axonframework.messaging.eventhandling.DomainEventMessage createDomainEvent(String eventId, String aggregateId,
                                                                                                 long sequenceNumber) {
        return createDomainEvent(AGGREGATE_TYPE, eventId, aggregateId, sequenceNumber, PAYLOAD, METADATA);
    }

    public static DomainEventMessage createDomainEvent(String type,
                                                       String eventId,
                                                       String aggregateId,
                                                       long sequenceNumber,
                                                       String payload,
                                                       Metadata metadata) {
        return new org.axonframework.messaging.eventhandling.GenericDomainEventMessage(type,
                                                                                       aggregateId,
                                                                                       sequenceNumber,
                                                                                       eventId,
                                                                                       TYPE,
                                                                                       payload,
                                                                                       metadata,
                                                                                       ClockUtils.instant());
    }
}
