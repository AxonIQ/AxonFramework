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

import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException.conflictingEventsDetected;

/**
 * Utility class for handling various operations related to the Aggregate-based Event Storage Engine.
 *
 * @author Mateusz Nowak
 * @author Allard Buijze
 * @since 5.0.0
 */
public final class AggregateBasedEventStorageEngineUtils {

    /**
     * Validates the tags associated with a list of event messages. Ensures that no event has more than one tag, as the
     * Event Storage engine in Aggregate mode only supports a single tag per event.
     *
     * @param events The list of tagged event messages to validate.
     * @throws TooManyTagsOnEventMessageException if any event has more than one tag.
     */
    public static void assertValidTags(List<TaggedEventMessage<?>> events) {
        for (TaggedEventMessage<?> taggedEvent : events) {
            if (taggedEvent.tags().size() > 1) {
                throw new TooManyTagsOnEventMessageException(
                        "An Event Storage engine in Aggregate mode does not support multiple tags per event",
                        taggedEvent.event(),
                        taggedEvent.tags());
            }
        }
    }

    /**
     * Resolves the aggregate identifier from the provided set of tags. The set must contain exactly one tag, and its
     * value will be returned as the aggregate identifier.
     *
     * @param tags The set of tags to resolve the aggregate identifier from.
     * @return The aggregate identifier, or {@code null} if the set is empty.
     * @throws IllegalArgumentException if the set contains more than one tag.
     */
    @Nullable
    public static String resolveAggregateIdentifier(Set<Tag> tags) {
        if (tags.isEmpty()) {
            return null;
        } else if (tags.size() > 1) {
            throw new IllegalArgumentException("Condition must provide exactly one tag");
        } else {
            return tags.iterator().next().value();
        }
    }

    /**
     * Resolves the aggregate type from the provided set of tags. The set must contain exactly one tag, and its key will
     * be returned as the aggregate type.
     *
     * @param tags The set of tags to resolve the aggregate type from.
     * @return The aggregate type, or {@code null} if the set is empty.
     * @throws IllegalArgumentException if the set contains more than one tag.
     */
    @Nullable
    public static String resolveAggregateType(Set<Tag> tags) {
        if (tags.isEmpty()) {
            return null;
        } else if (tags.size() > 1) {
            throw new IllegalArgumentException("Condition must provide exactly one tag");
        } else {
            return tags.iterator().next().key();
        }
    }

    /**
     * Translates the given {@code Exception} into an {@link AppendEventsTransactionRejectedException} if it is
     * identified as a conflict through the given {@code isConflictException} predicate. If the exception is not a
     * conflict, it recursively checks the cause of the exception.
     *
     * @param consistencyMarker   The consistency marker used to identify conflicting events.
     * @param exception           The exception to translate.
     * @param isConflictException A predicate used to check if the exception is a conflict.
     * @return The translated exception.
     */
    public static Exception translateConflictException(
            ConsistencyMarker consistencyMarker,
            Exception exception,
            Predicate<Throwable> isConflictException
    ) {
        Throwable root = exception;

        while (root != null && !isConflictException.test(root)) {
            root = root.getCause();
        }

        if (root == null) {
            // no conflict detected, return original exception
            return exception;
        }

        AppendEventsTransactionRejectedException translated = conflictingEventsDetected(consistencyMarker);

        translated.addSuppressed(exception);

        return translated;
    }

    /**
     * Translates the given {@code Throwable} into an {@link AppendEventsTransactionRejectedException} if it is
     * identified as a conflict through the given {@code isConflictException} predicate. If the exception is not a
     * conflict, it recursively checks the cause of the exception.
     *
     * @param consistencyMarker   The consistency marker used to identify conflicting events.
     * @param e                   The exception to translate.
     * @param isConflictException A predicate used to check if the exception is a conflict.
     * @return The translated exception.
     */
    public static Throwable translateConflictException(
            ConsistencyMarker consistencyMarker,
            Throwable e,
            Predicate<Throwable> isConflictException
    ) {
        if (isConflictException.test(e)) {
            AppendEventsTransactionRejectedException translated = conflictingEventsDetected(consistencyMarker);
            translated.addSuppressed(e);
            return translated;
        }
        if (e.getCause() != null) {
            Throwable translatedCause = translateConflictException(consistencyMarker,
                                                                   e.getCause(),
                                                                   isConflictException);
            if (translatedCause != e.getCause()) {
                return translatedCause;
            }
        }
        return e;
    }

    private AggregateBasedEventStorageEngineUtils() {
        // Utility class
    }
}
