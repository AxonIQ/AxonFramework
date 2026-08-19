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

package org.axonframework.eventsourcing.eventstore.jpa;

import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.eventsourcing.eventstore.EventCoordinator;
import org.axonframework.eventsourcing.eventstore.EventTypeResolver;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.BuilderUtils.assertPositive;
import static org.axonframework.common.BuilderUtils.assertStrictPositive;

/**
 * Configuration class of the {@link AggregateBasedJpaEventStorageEngine}.
 * <p>
 * This configuration class can be customized through its many builder methods. For a default configuration set use the
 * {@link AggregateBasedJpaEventStorageEngineConfiguration#DEFAULT} constant.
 *
 * @param persistenceExceptionResolver The {@link PersistenceExceptionResolver} used to detect concurrency exceptions
 *                                     from the backing database.
 * @param finalBatchPredicate          The predicate used to recognize the terminal batch when
 *                                     {@link
 *                                     org.axonframework.eventsourcing.eventstore.EventStorageEngine#source(SourcingCondition)
 *                                     sourcing} events. Defaults to the first empty batch.
 * @param eventCoordinator             The {@link EventCoordinator} to use. Defaults to
 *                                     {@link EventCoordinator#SIMPLE}.
 * @param batchSize                    The batch size used to retrieve events from the storage layer. Defaults to
 *                                     {@code 100}.
 * @param gapCleaningThreshold         The threshold of the number of gaps in a {@link GapAwareTrackingToken} before an
 *                                     attempt to clean them up. Defaults to an integer of {@code 250}.
 * @param maxGapOffset                 The maximum distance in sequence numbers between a gap and the event with the
 *                                     highest known index. Defaults to an integer of {@code 10000}.
 * @param lowestGlobalSequence         Value the first expected (auto generated)
 *                                     {@link AggregateEventEntry#globalIndex() global index} of an
 *                                     {@link AggregateEventEntry}. Defaults to {@code 1}.
 * @param gapTimeout                   The amount of time until a gap in a {@link GapAwareTrackingToken} may be
 *                                     considered timed out and thus ready for removal. Defaults to {@code 60000}ms.
 * @param eventTypeResolver            The {@link EventTypeResolver} used to construct a
 *                                     {@link org.axonframework.messaging.core.MessageType} from the stored qualified
 *                                     name and version when reading events. Defaults to
 *                                     {@link EventTypeResolver#DEFAULT}, which substitutes
 *                                     {@link EventTypeResolver#MISSING_VERSION_DEFAULT} for any missing or empty stored
 *                                     version, enabling AF4 events without a revision to be read.
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 4.0.0
 */
public record AggregateBasedJpaEventStorageEngineConfiguration(
        @Nullable PersistenceExceptionResolver persistenceExceptionResolver,
        Predicate<List<? extends AggregateEventEntry>> finalBatchPredicate,
        EventCoordinator eventCoordinator,
        int batchSize,
        int gapCleaningThreshold,
        int maxGapOffset,
        long lowestGlobalSequence,
        int gapTimeout,
        EventTypeResolver eventTypeResolver
) {

    private static final Predicate<List<? extends AggregateEventEntry>> DEFAULT_BATCH_PREDICATE = List::isEmpty;
    private static final EventCoordinator DEFAULT_EVENT_COORDINATOR = EventCoordinator.SIMPLE;
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_GAP_CLEANING_THRESHOLD = 250;
    private static final int DEFAULT_MAX_GAP_OFFSET = 10000;
    private static final long DEFAULT_LOWEST_GLOBAL_SEQUENCE = 1;
    private static final int DEFAULT_GAP_TIMEOUT = 60000;

    /**
     * A default instance of the {@link AggregateBasedJpaEventStorageEngineConfiguration}.
     */
    public static final AggregateBasedJpaEventStorageEngineConfiguration DEFAULT =
            new AggregateBasedJpaEventStorageEngineConfiguration(null,
                                                                 DEFAULT_BATCH_PREDICATE,
                                                                 DEFAULT_EVENT_COORDINATOR,
                                                                 DEFAULT_BATCH_SIZE,
                                                                 DEFAULT_GAP_CLEANING_THRESHOLD,
                                                                 DEFAULT_MAX_GAP_OFFSET,
                                                                 DEFAULT_LOWEST_GLOBAL_SEQUENCE,
                                                                 DEFAULT_GAP_TIMEOUT,
                                                                 EventTypeResolver.DEFAULT);

    /**
     * Compact constructor validating that the given {@code key} and {@code value} are not {@code null}.
     */
    @SuppressWarnings("MissingJavadoc")
    public AggregateBasedJpaEventStorageEngineConfiguration {
        requireNonNull(finalBatchPredicate, "The finalBatchPredicate must not be null.");
        requireNonNull(eventCoordinator, "The eventCoordinator must not be null.");
        assertStrictPositive(batchSize, "The batchSize must be a positive number.");
        assertPositive(gapCleaningThreshold, "gapCleaningThreshold");
        assertPositive(maxGapOffset, "The maxGapOffset must be a positive number.");
        assertPositive(lowestGlobalSequence, "The lowestGlobalSequence must be a positive number.");
        assertPositive(gapTimeout, "gapTimeout");
        requireNonNull(eventTypeResolver, "The eventTypeResolver must not be null.");
    }

    /**
     * Sets the {@link PersistenceExceptionResolver} used to detect concurrency exceptions from the backing database.
     * <p>
     * If the {@code persistenceExceptionResolver} is not specified, persistence exceptions are not explicitly
     * resolved.
     * <p>
     * Can be set to the {@link SQLErrorCodesResolver}, for example.
     *
     * @param persistenceExceptionResolver the {@link PersistenceExceptionResolver} used to detect concurrency
     *                                     exceptions from the backing database
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration persistenceExceptionResolver(
            @Nullable PersistenceExceptionResolver persistenceExceptionResolver
    ) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Defines the predicate used to recognize the terminal batch when
     * {@link org.axonframework.eventsourcing.eventstore.EventStorageEngine#source(SourcingCondition) sourcing} events.
     * <p>
     * Defaults to the first empty batch.
     *
     * @param finalBatchPredicate the predicate that indicates whether a given batch is to be considered the final batch
     *                            of an event stream
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration finalBatchPredicate(
            Predicate<List<? extends AggregateEventEntry>> finalBatchPredicate
    ) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Defines the {@link EventCoordinator} to use.
     * <p>
     * Defaults to {@link EventCoordinator#SIMPLE}.
     *
     * @param eventCoordinator the {@link EventCoordinator} to use, cannot be {@code null}
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration eventCoordinator(
            EventCoordinator eventCoordinator) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Sets the {@code batchSize} specifying the number of events that should be read at each database access. When more
     * than this number of events must be read to rebuild an aggregate's state, the events are read in batches of this
     * size.
     * <p>
     * Defaults to {@code 100}.
     * <p>
     * <b>Tip:</b> When using a snapshotter, make sure to choose snapshot trigger and batch size such that a single
     * batch will generally retrieve all events required to rebuild an aggregate's state.
     *
     * @param batchSize an {@code int} specifying the number of events that should be read at each database access
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration batchSize(int batchSize) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Sets the threshold of number of gaps in a token before an attempt to clean gaps up is taken.
     * <p>
     * Defaults to {@code 250}.
     *
     * @param gapCleaningThreshold an {@code int} specifying the threshold of number of gaps in a token before an
     *                             attempt to clean gaps up is taken
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration gapCleaningThreshold(int gapCleaningThreshold) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Sets the {@code maxGapOffset} specifying the maximum distance in sequence numbers between a missing event and the
     * event with the highest known index.
     * <p>
     * If the gap is bigger it is assumed that the missing event will not be committed to the store anymore. This event
     * storage engine will no longer look for those events the next time a batch is fetched.
     * <p>
     * Defaults to {@code 10000}.
     *
     * @param maxGapOffset an {@code int} specifying the maximum distance in sequence numbers between a missing event
     *                     and the event with the highest known index
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration maxGapOffset(int maxGapOffset) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Sets the {@code lowestGlobalSequence} specifying the first expected auto generated sequence number. For most data
     * stores this is 1 unless the table has contained entries before.
     * <p>
     * Defaults to {@code 1}.
     *
     * @param lowestGlobalSequence a {@code long} specifying the first expected auto generated sequence number
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration lowestGlobalSequence(long lowestGlobalSequence) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Sets the amount of time until a gap in a {@link GapAwareTrackingToken} may be considered timed out and thus ready
     * for removal.
     * <p>
     * This setting will affect the cleaning process of gaps. Gaps that have timed out will be removed from Tracking
     * Tokens to improve performance of reading events.
     * <p>
     * Defaults to {@code 60000}ms.
     *
     * @param gapTimeout an {@code int} specifying the amount of time in milliseconds until a 'gap' in a TrackingToken
     *                   may be considered timed out
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration gapTimeout(int gapTimeout) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    gapTimeout,
                                                                    this.eventTypeResolver);
    }

    /**
     * Sets the {@link EventTypeResolver} used to construct a {@link org.axonframework.messaging.core.MessageType} from
     * the stored qualified name and version when reading events.
     * <p>
     * Defaults to {@link EventTypeResolver#DEFAULT}, which substitutes
     * {@link EventTypeResolver#MISSING_VERSION_DEFAULT} for any missing or empty stored version. Override this when the
     * default substituted version does not suit your migration needs.
     *
     * @param eventTypeResolver the resolver to use; must not be {@code null}
     * @return a new configuration instance, for fluent interfacing
     */
    public AggregateBasedJpaEventStorageEngineConfiguration eventTypeResolver(
            EventTypeResolver eventTypeResolver
    ) {
        return new AggregateBasedJpaEventStorageEngineConfiguration(this.persistenceExceptionResolver,
                                                                    this.finalBatchPredicate,
                                                                    this.eventCoordinator,
                                                                    this.batchSize,
                                                                    this.gapCleaningThreshold,
                                                                    this.maxGapOffset,
                                                                    this.lowestGlobalSequence,
                                                                    this.gapTimeout,
                                                                    eventTypeResolver);
    }
}
