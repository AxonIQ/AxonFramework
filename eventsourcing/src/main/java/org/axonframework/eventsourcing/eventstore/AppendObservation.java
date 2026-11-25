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

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The terminal outcome of a single append, as reported to an {@link AppendObserver} by the
 * {@link AppendObservingEventStorageEngine}.
 * <p>
 * Next to the {@link Outcome outcome}, an observation carries what was appended: the {@link AppendCondition} the
 * append was validated against, the {@link TaggedEventMessage events} offered to the
 * {@link EventStorageEngine}, and the {@link ProcessingContext} of the append, if any. The {@link #events()} are a
 * snapshot taken when the append started, so later changes to the list given to the engine do not show here.
 * <p>
 * The {@link #context()} may already be committing or completed when the observation is made. Use it to read
 * resources only, and do not retain the observation beyond the {@link AppendObserver#observe(AppendObservation) call}
 * that received it.
 * <p>
 * Use pattern matching on the {@link Outcome} to react to a specific kind of outcome:
 * <pre>{@code
 * AppendObserver observer = observation -> {
 *     switch (observation.outcome()) {
 *         case AppendObservation.Outcome.Committed committed -> recordSuccess(committed.consistencyMarker());
 *         case AppendObservation.Outcome.Rejected rejected -> recordConflict(rejected.exception().tags());
 *         case AppendObservation.Outcome.Failed failed -> recordFailure(failed.cause());
 *         case AppendObservation.Outcome.RolledBack rolledBack -> recordRollback();
 *     }
 * };
 * }</pre>
 *
 * @param condition the {@link AppendCondition} the append was validated against
 * @param events    the {@link TaggedEventMessage events} offered for the append, as an unmodifiable list
 * @param context   the {@link ProcessingContext} the append took place in, or {@code null} if it was appended
 *                  outside of one; it may already be committing or completed when the observation is made
 * @param outcome   the terminal {@link Outcome} of the append
 * @author Alexey Afanasyev
 * @see AppendObserver
 * @since 5.5.0
 */
public record AppendObservation(AppendCondition condition,
                                List<TaggedEventMessage<?>> events,
                                @Nullable ProcessingContext context,
                                Outcome outcome) {

    /**
     * Compact constructor validating that the {@code condition}, {@code events} and {@code outcome} are not
     * {@code null}, and taking an unmodifiable copy of the given {@code events}.
     *
     * @param condition the {@link AppendCondition} the append was validated against
     * @param events    the {@link TaggedEventMessage events} offered for the append
     * @param context   the {@link ProcessingContext} the append took place in, or {@code null} if it was appended
     *                  outside of one
     * @param outcome   the terminal {@link Outcome} of the append
     */
    public AppendObservation {
        Objects.requireNonNull(condition, "The condition parameter cannot be null.");
        Objects.requireNonNull(events, "The events parameter cannot be null.");
        events = List.copyOf(events);
        Objects.requireNonNull(outcome, "The outcome parameter cannot be null.");
    }

    /**
     * The terminal outcome of an append: exactly one of {@link Committed}, {@link Rejected}, {@link Failed} or
     * {@link RolledBack}.
     */
    public sealed interface Outcome {

        /**
         * The append was committed, making its events visible to consumers.
         *
         * @param consistencyMarker the {@link ConsistencyMarker} the {@link EventStorageEngine} returned for the
         *                          committed append
         */
        record Committed(ConsistencyMarker consistencyMarker) implements Outcome {

            /**
             * Compact constructor validating that the {@code consistencyMarker} is not {@code null}.
             *
             * @param consistencyMarker the {@link ConsistencyMarker} the {@link EventStorageEngine} returned for the
             *                          committed append
             */
            public Committed {
                Objects.requireNonNull(consistencyMarker, "The consistencyMarker parameter cannot be null.");
            }
        }

        /**
         * The append was rejected because events matching the {@link AppendCondition#criteria() criteria} of its
         * {@link AppendCondition} were appended beyond its {@link AppendCondition#consistencyMarker() consistency
         * marker}.
         * <p>
         * The rejection may be detected when the append starts or when it is committed, depending on the
         * {@link EventStorageEngine}. The {@link AppendEventsTransactionRejectedException} carries the
         * {@link AppendEventsTransactionRejectedException#tags() tags} that were checked and, when the wrapped engine
         * diagnosed the conflict, the {@link AppendEventsTransactionRejectedException#conflictingEvent() conflicting
         * event}.
         *
         * @param exception the {@link AppendEventsTransactionRejectedException} describing the rejection
         */
        record Rejected(AppendEventsTransactionRejectedException exception) implements Outcome {

            /**
             * Compact constructor validating that the {@code exception} is not {@code null}.
             *
             * @param exception the {@link AppendEventsTransactionRejectedException} describing the rejection
             */
            public Rejected {
                Objects.requireNonNull(exception, "The exception parameter cannot be null.");
            }
        }

        /**
         * The append failed for a reason other than a violated {@link AppendCondition}, for example because the
         * underlying storage was unavailable.
         * <p>
         * A failed append may still have stored its events, for example when the failure happened after the commit,
         * or when the storage did not confirm the commit.
         *
         * @param cause the failure, with any {@link java.util.concurrent.CompletionException} or
         *              {@link java.util.concurrent.ExecutionException} wrapper removed
         */
        record Failed(Throwable cause) implements Outcome {

            /**
             * Compact constructor validating that the {@code cause} is not {@code null}.
             *
             * @param cause the failure, with any {@link java.util.concurrent.CompletionException} or
             *              {@link java.util.concurrent.ExecutionException} wrapper removed
             */
            public Failed {
                Objects.requireNonNull(cause, "The cause parameter cannot be null.");
            }
        }

        /**
         * The append was rolled back before any other outcome was reported, typically because the surrounding
         * {@link ProcessingContext} failed after the events were handed to the {@link EventStorageEngine}.
         * <p>
         * A rollback that follows a successful commit is not reported as rolled back, since the events are stored by
         * then.
         */
        record RolledBack() implements Outcome {

        }
    }
}
