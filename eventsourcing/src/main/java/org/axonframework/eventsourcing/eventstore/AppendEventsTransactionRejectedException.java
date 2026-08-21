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

import org.axonframework.common.AxonNonTransientException;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.Set;

/**
 * Exception indicating that a transaction was rejected due to conflicts detected in the events to append.
 *
 * @author Steven van Beelen
 * @author Allard Buijze
 * @author John Hendrikx
 * @since 5.0.0
 */
public class AppendEventsTransactionRejectedException extends AxonNonTransientException {

    private final Set<Tag> tags;
    private final @Nullable EventMessage conflictingEvent;

    /**
     * Constructs an {@code AppendEventsTransactionRejectedException} with the given {@code message}.
     *
     * @param message the message of the {@code AppendEventsTransactionRejectedException} under construction
     */
    public AppendEventsTransactionRejectedException(String message) {
        this(message, Set.of(), null);
    }

    /**
     * Constructs an {@code AppendEventsTransactionRejectedException} with the given {@code message}, noting the
     * {@code tags} of the {@link AppendCondition#criteria() criteria} that were violated.
     *
     * @param message the message of the {@code AppendEventsTransactionRejectedException} under construction
     * @param tags    the {@link Tag Tags} of the {@link AppendCondition#criteria() criteria} that were checked when
     *                the rejection occurred
     */
    public AppendEventsTransactionRejectedException(String message, Set<Tag> tags) {
        this(message, tags, null);
    }

    private AppendEventsTransactionRejectedException(String message, Set<Tag> tags,
                                                     @Nullable EventMessage conflictingEvent) {
        super(message);

        this.tags = Set.copyOf(tags);
        this.conflictingEvent = conflictingEvent;
    }

    /**
     * Constructs an {@code AppendEventsTransactionRejectedException} noting that the {@link EventStorageEngine}
     * contains events matching the {@link AppendCondition#criteria() criteria} passed the given
     * {@code consistencyMarker}.
     *
     * @param consistencyMarker the pointer in the {@link EventStorageEngine} after which no events should've been
     *                          appended that match the {@link EventCriteria} of an {@link AppendCondition}
     * @return an {@code AppendEventsTransactionRejectedException} noting that the {@link EventStorageEngine} contains
     *     events matching the {@link AppendCondition#criteria() criteria} passed the given {@code consistencyMarker}
     */
    public static AppendEventsTransactionRejectedException conflictingEventsDetected(
            ConsistencyMarker consistencyMarker
    ) {
        return conflictingEventsDetected(consistencyMarker, Set.of());
    }

    /**
     * Constructs an {@code AppendEventsTransactionRejectedException} noting that the {@link EventStorageEngine}
     * contains events matching the {@link AppendCondition#criteria() criteria} passed the given
     * {@code consistencyMarker}.
     *
     * @param consistencyMarker the pointer in the {@link EventStorageEngine} after which no events should've been
     *                          appended that match the {@link EventCriteria} of an {@link AppendCondition}
     * @param tags              the {@link Tag Tags} of the {@link AppendCondition#criteria() criteria} that were
     *                          checked when the rejection occurred
     * @return an {@code AppendEventsTransactionRejectedException} noting that the {@link EventStorageEngine} contains
     *     events matching the {@link AppendCondition#criteria() criteria} passed the given {@code consistencyMarker}
     */
    public static AppendEventsTransactionRejectedException conflictingEventsDetected(
            ConsistencyMarker consistencyMarker, Set<Tag> tags
    ) {
        return new AppendEventsTransactionRejectedException(
                "Event matching append criteria have been detected beyond provided consistency marker: "
                        + consistencyMarker,
                tags
        );
    }

    /**
     * The {@link Tag Tags} of the {@link AppendCondition#criteria() criteria} that were checked when this rejection
     * occurred.
     *
     * @return the {@link Tag Tags} of the {@link AppendCondition#criteria() criteria} that were checked when this
     *     rejection occurred, or an empty {@link Set} if unknown
     */
    public Set<Tag> tags() {
        return tags;
    }

    /**
     * The first event matching the {@link AppendCondition#criteria() criteria} found beyond the violated consistency
     * marker, if looked up; empty otherwise.
     *
     * @return the first conflicting event, or empty if not looked up
     */
    public Optional<EventMessage> conflictingEvent() {
        return Optional.ofNullable(conflictingEvent);
    }

    /**
     * Returns a copy of {@code this AppendEventsTransactionRejectedException} with its {@link #conflictingEvent()}
     * replaced by the given {@code conflictingEvent}, preserving the original message, {@link #tags()}, stack trace,
     * and cause.
     *
     * @param conflictingEvent an event that conflicted with the {@link AppendCondition#criteria() criteria}, or
     *                         {@code null} if none was found
     * @return a copy of {@code this AppendEventsTransactionRejectedException} carrying the given
     *     {@code conflictingEvent}
     */
    public AppendEventsTransactionRejectedException withConflictingEvent(@Nullable EventMessage conflictingEvent) {
        AppendEventsTransactionRejectedException copy =
                new AppendEventsTransactionRejectedException(getMessage(), tags, conflictingEvent);

        copy.setStackTrace(getStackTrace());
        copy.initCause(getCause());

        return copy;
    }
}
