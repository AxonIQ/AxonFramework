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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Interface describing the actions that can be taken on a transaction to source a model from the {@link EventStore}
 * based on the resulting {@link MessageStream}.
 * <p>
 * Note that this transaction includes operations for {@link #source(SourcingCondition)} the model as well as
 * {@link #appendEvent(EventMessage) appending events}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface EventStoreTransaction {

    /**
     * Sources a {@link MessageStream} of type {@link EventMessage} based on the given {@code condition} that can be
     * used to rehydrate a model.
     * <p>
     * Note that using {@link EventCriteria#havingAnyTag no criteria} does not make sense for sourcing, as it is
     * <b>not</b> recommended to source the entire event store.
     * <p>
     * <b>Any</b> {@code EventStoreTransaction} using the {@link EventStorageEngine#source(SourcingCondition)} is
     * expected to {@link MessageStream#filter(Predicate) filter} the {@link TerminalEventMessage} with the
     * {@link ConsistencyMarker}.
     *
     * @param condition The {@link SourcingCondition} used to retrieve the {@link MessageStream} containing the sequence
     *                  of events that can rehydrate a model.
     * @return The {@link MessageStream} of type {@link EventMessage} containing the event sequence complying to the
     * given {@code condition}.
     */
    default MessageStream<? extends EventMessage> source(SourcingCondition condition) {
        return source(condition, null);
    }

    /**
     * Sources a {@link MessageStream} of type {@link EventMessage} based on the given {@code condition}, optionally
     * invoking the given {@link Position resume position} callback.
     * <p>
     * The provided {@code resumePositionCallback}, if non-{@code null}, is invoked at most once and only after the
     * returned {@link MessageStream} has been consumed completely. For most implementations, the
     * {@link Position resume position} is only known when the stream reaches its terminal event. As such, the callback
     * is guaranteed to be invoked only if the stream is fully consumed.
     * <p>
     * If sourcing completes and no events are found, the callback will be invoked with the position
     * specified in {@code sourcingCondition} or with a greater position. Returning a greater position
     * allows resuming from a point that already excludes positions known to be non-matching.
     * <p>
     * If the stream terminates with an error, is closed prematurely, or is not consumed to completion, the callback is
     * not guaranteed to be invoked.
     * <p>
     * The callback should not throw exceptions; doing so may result in undefined behavior.
     * <p>
     * Note that using {@link EventCriteria#havingAnyTag no criteria} does not make sense for sourcing, as it is
     * <b>not</b> recommended to source the entire event store.
     * <p>
     * <b>Any</b> {@code EventStoreTransaction} using the {@link EventStorageEngine#source(SourcingCondition)} is
     * expected to {@link MessageStream#filter(Predicate) filter} the {@link TerminalEventMessage} with the
     * {@link ConsistencyMarker}.
     *
     * @param condition              The {@link SourcingCondition} used to retrieve the {@link MessageStream} containing
     *                               the sequence of events that can rehydrate a model.
     * @param resumePositionCallback An optional callback that receives the {@link Position} from which sourcing may be
     *                               resumed once it becomes available; the position provided is never {@code null}.
     * @return The {@link MessageStream} of type {@link EventMessage} containing the event sequence complying to the
     * given {@code condition}.
     * @since 5.0.3
     */
    MessageStream<? extends EventMessage> source(
            SourcingCondition condition,
            @Nullable Consumer<Position> resumePositionCallback
    );

    /**
     * Appends an {@code eventMessage} to be appended to an {@link EventStore} in this transaction.
     *
     * @param eventMessage The {@link EventMessage} to append.
     */
    void appendEvent(EventMessage eventMessage);

    /**
     * Registers a {@code callback} to invoke when an event is {@link #appendEvent(EventMessage) appended} to this
     * transaction.
     * <p>
     * Each {@code callback} registration adds a new callback that is invoked on the
     * {@code appendEvent(EventMessage, AppendCondition)} operation.
     *
     * @param callback A {@link Consumer} to invoke when an event is appended in this transaction.
     */
    void onAppend(Consumer<EventMessage> callback);

    /**
     * Returns the position in the event store of the last {@link #appendEvent(EventMessage) appended} event by this
     * transaction.
     * <p>
     * Will return {@link ConsistencyMarker#ORIGIN} if nothing has been appended yet.
     *
     * @return The position in the event store of the last {@link #appendEvent(EventMessage) appended} event by this
     * transaction.
     */
    ConsistencyMarker appendPosition();
}
