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

package org.axonframework.test.saga;

import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.annotation.SourceId;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.SequenceNumber;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries the Axon Framework 4 aggregate envelope fields from a fixture's aggregate publisher to the Saga handling the
 * event.
 * <p>
 * Axon Framework 4 published these on a {@code DomainEventMessage}, which Axon Framework 5 does not have. It keeps the
 * same three fields as {@link LegacyResources} on the processing context instead, where
 * {@link SourceId}, {@link org.axonframework.messaging.core.annotation.AggregateType AggregateType} and
 * {@link SequenceNumber} annotated handler parameters read them.
 * <p>
 * A {@code ProcessingContext} is not reachable from the fixture's publishing calls, so the fields travel as metadata
 * and are lifted onto the context by {@link #liftingInterceptor() an interceptor} on the Saga's event processor. The
 * metadata is removed again before the Saga sees the message, so a Saga asserting on its own metadata is unaffected.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class LegacyAggregateEnvelope {

    private static final String TYPE = "axon-legacy-aggregate-type";
    private static final String IDENTIFIER = "axon-legacy-aggregate-identifier";
    private static final String SEQUENCE_NUMBER = "axon-legacy-aggregate-sequence-number";

    private LegacyAggregateEnvelope() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * The given {@code metadata} with the aggregate envelope fields added.
     *
     * @param metadata            the metadata the caller wants on the event
     * @param aggregateType       the type of the aggregate the event appears to come from
     * @param aggregateIdentifier the identifier of the aggregate the event appears to come from
     * @param sequenceNumber      the sequence number of the event within that aggregate's stream
     * @return the metadata to publish the event with
     */
    static Map<String, String> withAggregateFields(Map<String, String> metadata,
                                                   String aggregateType,
                                                   String aggregateIdentifier,
                                                   long sequenceNumber) {
        Map<String, String> result = new HashMap<>(metadata);
        result.put(TYPE, aggregateType);
        result.put(IDENTIFIER, aggregateIdentifier);
        result.put(SEQUENCE_NUMBER, Long.toString(sequenceNumber));
        return result;
    }

    /**
     * An interceptor lifting the aggregate envelope fields off the message's metadata and onto the processing context,
     * where the handler parameter resolvers find them.
     *
     * @return the interceptor to register on the Saga's event processor
     */
    static MessageHandlerInterceptor<? super EventMessage> liftingInterceptor() {
        return (message, context, chain) -> {
            Map<String, String> metadata = message.metadata();
            String aggregateType = metadata.get(TYPE);
            if (aggregateType == null) {
                return chain.proceed(message, context);
            }
            context.putResource(LegacyResources.AGGREGATE_TYPE_KEY, aggregateType);
            context.putResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, metadata.get(IDENTIFIER));
            context.putResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY,
                                Long.valueOf(metadata.get(SEQUENCE_NUMBER)));

            Map<String, String> withoutAggregateFields = new HashMap<>(metadata);
            withoutAggregateFields.remove(TYPE);
            withoutAggregateFields.remove(IDENTIFIER);
            withoutAggregateFields.remove(SEQUENCE_NUMBER);
            return chain.proceed(((EventMessage) message).withMetadata(withoutAggregateFields), context);
        };
    }
}
