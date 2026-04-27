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

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.axonframework.common.TypeReference;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.conversion.Converter;
import org.axonframework.eventsourcing.eventstore.AggregateBasedConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.AggregateBasedConsistencyMarker.AggregateSequencer;
import org.axonframework.eventsourcing.eventstore.AggregateBasedEventStorageEngineUtils;
import org.axonframework.eventsourcing.eventstore.AggregateSequenceNumberPosition;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.ContinuousMessageStream;
import org.axonframework.eventsourcing.eventstore.EmptyAppendTransaction;
import org.axonframework.eventsourcing.eventstore.EventCoordinator;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StreamSpliterator;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.TerminalEventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriterion;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.DateTimeUtils.formatInstant;
import static org.axonframework.eventsourcing.eventstore.AggregateBasedEventStorageEngineUtils.*;


/**
 * An {@link EventStorageEngine} implementation that uses JPA to store and fetch events from an aggregate-based event
 * storage solution.
 * <p>
 * By default, the payload of events is stored as a converted blob of bytes. Other columns are used to store meta-data
 * that allow quick finding of events for a specific aggregates in the correct order.
 *
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class AggregateBasedJpaEventStorageEngine implements EventStorageEngine {

    private static final Logger logger = LoggerFactory.getLogger(AggregateBasedJpaEventStorageEngine.class);

    private static final TypeReference<Map<String, String>> METADATA_MAP_TYPE_REF = new TypeReference<>() {
    };

    private static final String FIRST_TOKEN_QUERY = "SELECT COALESCE(MIN(e.globalIndex) - 1, -1) FROM AggregateEventEntry e";
    private static final String LATEST_TOKEN_QUERY = "SELECT COALESCE(MAX(e.globalIndex), -1) FROM AggregateEventEntry e";
    private static final String TOKEN_AT_QUERY = """
            SELECT COALESCE(MIN(e.globalIndex) - 1, (SELECT MAX(a.globalIndex) FROM AggregateEventEntry a), -1) \
            FROM AggregateEventEntry e \
            WHERE e.timestamp >= :dateTime""";
    private static final String EVENTS_BY_AGGREGATE_QUERY = """
            SELECT e \
            FROM AggregateEventEntry e \
            WHERE e.aggregateIdentifier = :id \
            AND e.aggregateSequenceNumber >= :seq \
            ORDER BY e.aggregateSequenceNumber ASC""";
    private static final String EVENTS_BY_TOKEN_QUERY = """
            SELECT e \
            FROM AggregateEventEntry e \
            WHERE e.globalIndex > :token \
            ORDER BY e.globalIndex ASC""";
    private static final String EVENTS_BY_GAPPED_TOKEN = """
            SELECT e \
            FROM AggregateEventEntry e \
            WHERE e.globalIndex > :token \
            OR e.globalIndex \
            IN :gaps \
            ORDER BY e.globalIndex ASC""";
    private static final String INDEX_AND_TIMESTAMP_QUERY = """
            SELECT e.globalIndex, e.timestamp
            FROM AggregateEventEntry e \
            WHERE e.globalIndex >= :firstGapOffset \
            AND e.globalIndex <= :maxGlobalIndex""";

    private final TransactionalExecutorProvider<EntityManager> transactionalExecutorProvider;
    private final EventConverter converter;

    private final PersistenceExceptionResolver persistenceExceptionResolver;
    private final Predicate<List<? extends AggregateEventEntry>> finalBatchPredicate;
    private final int batchSize;
    private final int gapCleaningThreshold;
    private final int maxGapOffset;
    private final long lowestGlobalSequence;

    private final GapAwareTrackingTokenOperations tokenOperations;
    private final Predicate<Throwable> isConflictException;

    /**
     * Tracks runnables for callbacks attached to streams for when new events may have become available.
     */
    private final Map<Object, Runnable> streamCallbacks = new ConcurrentHashMap<>();

    private EventCoordinator.Handle eventCoordinatorHandle;

    /**
     * Constructs an {@code AggregateBasedJpaEventStorageEngine} with the given parameters.
     *
     * @param transactionalExecutorProvider The transactional executor provider for this storage solution.
     * @param converter             The converter used to convert the {@link EventMessage#payload()} and
     *                              {@link EventMessage#metadata()} to a {@code byte[]}.
     * @param configurer            A unary operator of the {@link AggregateBasedJpaEventStorageEngineConfiguration}
     *                              that customizes the {@code AggregateBasedJpaEventStorageEngine} under construction.
     */
    public AggregateBasedJpaEventStorageEngine(TransactionalExecutorProvider<EntityManager> transactionalExecutorProvider,
                                               EventConverter converter,
                                               UnaryOperator<AggregateBasedJpaEventStorageEngineConfiguration> configurer) {
        this.transactionalExecutorProvider =
                requireNonNull(transactionalExecutorProvider, "The transactionalExecutorProvider may not be null.");
        this.converter = requireNonNull(converter, "The converter may not be null.");

        var config = requireNonNull(configurer, "The configurer may not be null.")
                .apply(AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT);
        this.persistenceExceptionResolver = config.persistenceExceptionResolver();
        this.finalBatchPredicate = config.finalBatchPredicate();
        this.batchSize = config.batchSize();
        this.gapCleaningThreshold = config.gapCleaningThreshold();
        this.lowestGlobalSequence = config.lowestGlobalSequence();
        this.maxGapOffset = config.maxGapOffset();

        this.tokenOperations = new GapAwareTrackingTokenOperations(config.gapTimeout(), logger);
        this.eventCoordinatorHandle = config.eventCoordinator().startCoordination(this::onAppendDetected);
        this.isConflictException = t -> persistenceExceptionResolver != null
            && t instanceof Exception ex
            && persistenceExceptionResolver.isDuplicateKeyViolation(ex);
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                @Nullable ProcessingContext processingContext,
                                                                List<TaggedEventMessage<?>> events) {
        try {
            assertValidTags(events);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        if (events.isEmpty()) {
            return CompletableFuture.completedFuture(EmptyAppendTransaction.INSTANCE);
        }

        return entityManagerExecutor(processingContext).apply(em -> {
            AggregateBasedConsistencyMarker preCommitConsistencyMarker = AggregateBasedConsistencyMarker.from(condition);

            try {
                AggregateSequencer sequencer = preCommitConsistencyMarker.createSequencer();

                events.stream()
                    .map(taggedEvent -> mapToEntry(taggedEvent, sequencer, converter))
                    .forEach(em::persist);

                em.flush();  // flush so violations occur here and now, instead of in the commit phase

                return new AppendTransaction<AggregateBasedConsistencyMarker>() {
                    @Override
                    public CompletableFuture<AggregateBasedConsistencyMarker> commit() {
                        // Transaction is in control of the entity manager executor, so do no work here
                        return CompletableFuture.completedFuture(sequencer.toMarker());
                    }

                    @Override
                    public void rollback() {
                        // Transaction is in control of the entity manager executor, so do no work here
                    }

                    @Override
                    public CompletableFuture<ConsistencyMarker> afterCommit(AggregateBasedConsistencyMarker marker) {
                        eventCoordinatorHandle.onEventsAppended(events);

                        return CompletableFuture.completedFuture(marker);
                    }
                };
            }
            catch (Exception e) {
                throw AggregateBasedEventStorageEngineUtils
                    .translateConflictException(preCommitConsistencyMarker, e, isConflictException);
            }
        });
    }

    private static AggregateEventEntry mapToEntry(TaggedEventMessage<?> taggedEvent,
                                                  AggregateSequencer aggregateSequencer,
                                                  Converter converter) {
        EventMessage event = taggedEvent.event();
        Set<Tag> tags = taggedEvent.tags();
        String aggregateIdentifier = resolveAggregateIdentifier(tags);
        return new AggregateEventEntry(
                event.identifier(),
                event.type().name(),
                event.type().version(),
                event.payloadAs(byte[].class, converter),
                converter.convert(event.metadata(), byte[].class),
                event.timestamp(),
                resolveAggregateType(tags),
                resolveAggregateIdentifier(tags),
                aggregateIdentifier != null ? aggregateSequencer.incrementAndGetSequenceOf(aggregateIdentifier) : null
        );
    }

    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition) {
        CompletableFuture<Void> endOfStreams = new CompletableFuture<>();
        List<AggregateSource> aggregateSources = condition.criteria()
                                                          .flatten()
                                                          .stream()
                                                          .map(criterion -> this.aggregateSourceForCriterion(
                                                                  condition, criterion
                                                          ))
                                                          .toList();

        return aggregateSources.stream()
                               .map(AggregateSource::source)
                               .reduce(MessageStream.empty().cast(), MessageStream::concatWith)
                               .onComplete(() -> endOfStreams.complete(null))
                               .concatWith(MessageStream.fromFuture(
                                       endOfStreams.thenApply(event -> TerminalEventMessage.INSTANCE),
                                       unused -> Context.with(
                                               ConsistencyMarker.RESOURCE_KEY,
                                               combineAggregateMarkers(aggregateSources.stream())
                                       )
                               ));
    }

    private AggregateSource aggregateSourceForCriterion(SourcingCondition condition, EventCriterion criterion) {
        AtomicReference<@Nullable AggregateBasedConsistencyMarker> markerReference = new AtomicReference<>();
        var aggregateIdentifier = resolveAggregateIdentifier(criterion.tags());
        long firstSequenceNumber = AggregateSequenceNumberPosition.toSequenceNumber(condition.start());
        //noinspection DataFlowIssue
        StreamSpliterator<? extends AggregateEventEntry> entrySpliterator = new StreamSpliterator<>(
                lastEntry -> queryEventsBy(
                        aggregateIdentifier,
                        lastEntry != null && lastEntry.aggregateSequenceNumber() != null
                                ? lastEntry.aggregateSequenceNumber() + 1
                                : firstSequenceNumber
                ),
                finalBatchPredicate
        );

        MessageStream<EventMessage> source =
                MessageStream.fromStream(StreamSupport.stream(entrySpliterator, false),
                                         this::convertToEventMessage,
                                         entry -> setMarkerAndBuildContext(entry, markerReference))
                             // Defaults the marker when the aggregate stream was empty
                             .onComplete(() -> markerReference.compareAndSet(
                                     // making sequence number (=last seen event) -1 makes the position in the Consistency Marker 0
                                     // this ensures 0-based sequence numbers, as they are derived from the marker when appending
                                     null, new AggregateBasedConsistencyMarker(aggregateIdentifier, -1)
                             ))
                             .cast();
        return new AggregateSource(markerReference, source);
    }

    private List<AggregateEventEntry> queryEventsBy(String aggregateIdentifier, long firstSequenceNumber) {
        return entityManagerExecutor(null).apply(em ->
            em.createQuery(EVENTS_BY_AGGREGATE_QUERY, AggregateEventEntry.class)
                .setParameter("id", aggregateIdentifier)
                .setParameter("seq", firstSequenceNumber)
                .setMaxResults(batchSize)
                .getResultList()
        ).join();
    }

    private static Context setMarkerAndBuildContext(AggregateEventEntry entry,
                                                    AtomicReference<AggregateBasedConsistencyMarker> markerReference) {
        String aggregateId = Objects.requireNonNullElse(entry.aggregateIdentifier(), entry.aggregateIdentifier());
        String aggregateType = entry.aggregateType();
        Long aggregateSeqNo = Objects.requireNonNullElse(entry.aggregateSequenceNumber(), 0L);
        markerReference.set(new AggregateBasedConsistencyMarker(aggregateId, aggregateSeqNo));
        return buildContext(aggregateId, aggregateSeqNo, aggregateType);
    }

    private static ConsistencyMarker combineAggregateMarkers(Stream<AggregateSource> resultStream) {
        return resultStream.map(AggregateSource::markerReference)
                           .map(AtomicReference::get)
                           .map(marker -> (ConsistencyMarker) marker)
                           .reduce(ConsistencyMarker::upperBound)
                           .orElseThrow();
    }

    @Override
    public MessageStream<EventMessage> stream(StreamingCondition condition) {
        AtomicReference<GapAwareTrackingToken> cursorRef = new AtomicReference<>(tokenOperations.assertGapAwareTrackingToken(condition.position()));

        return new ContinuousMessageStream<>(
                () -> queryTokensAndEventsBy(cursorRef, condition),
                tae -> new SimpleEntry<>(convertToEventMessage(tae.event), buildTrackedContext(tae)),
                (ms, r) -> {
                    streamCallbacks.put(ms, r);
                    return () -> streamCallbacks.remove(ms) != null;
                }
        );
    }

    private List<TokenAndEvent> queryTokensAndEventsBy(AtomicReference<GapAwareTrackingToken> cursorRef, StreamingCondition condition) {
        return entityManagerExecutor(null).apply(em -> {
            List<TokenAndEvent> result = new ArrayList<>();
            GapAwareTrackingToken cleanedToken = cleanedToken(em, cursorRef.get());
            List<AggregateEventEntry> events = queryEventsBy(em, cleanedToken);

            GapAwareTrackingToken token = cleanedToken;

            Instant gapTimeoutThreshold = tokenOperations.gapTimeoutThreshold();
            for (AggregateEventEntry event : events) {
                String type = event.aggregateType();
                String identifier = event.aggregateIdentifier();

                // A null type or identifier is allowed, but those cannot form a valid tag:
                Set<Tag> tags = type == null || identifier == null ? Set.of() : Set.of(new Tag(type, identifier));

                // Always advance the cursor to track the furthest scanned position, regardless of match:
                token = calculateToken(token, event.globalIndex(), event.timestamp(), gapTimeoutThreshold);
                cursorRef.set(token);

                if (condition.matches(new QualifiedName(event.type()), tags)) {
                    result.add(new TokenAndEvent(token, event));
                }
            }
            return result;
        }).join();
    }

    private GapAwareTrackingToken cleanedToken(EntityManager entityManager, GapAwareTrackingToken lastToken) {
        return lastToken != null && lastToken.getGaps().size() > gapCleaningThreshold
                ? tokenOperations.withGapsCleaned(lastToken, indexAndTimestampBetweenGaps(entityManager, lastToken))
                : lastToken;
    }

    private List<Object[]> indexAndTimestampBetweenGaps(EntityManager entityManager, GapAwareTrackingToken token) {
        return entityManager.createQuery(INDEX_AND_TIMESTAMP_QUERY, Object[].class)
                            .setParameter("firstGapOffset", token.getGaps().first())
                            .setParameter("maxGlobalIndex", token.getGaps().last() + 1L)
                            .getResultList();
    }

    private List<AggregateEventEntry> queryEventsBy(EntityManager entityManager, GapAwareTrackingToken token) {
        TypedQuery<AggregateEventEntry> eventsByTokenQuery =
                token == null || token.getGaps().isEmpty()
                        ? entityManager.createQuery(EVENTS_BY_TOKEN_QUERY, AggregateEventEntry.class)
                        : entityManager.createQuery(EVENTS_BY_GAPPED_TOKEN, AggregateEventEntry.class)
                                       .setParameter("gaps", token.getGaps());

        return eventsByTokenQuery.setParameter("token", token == null ? -1L : token.getIndex())
                                 .setMaxResults(batchSize)
                                 .getResultList();
    }

    private GapAwareTrackingToken calculateToken(@Nullable GapAwareTrackingToken token,
                                                 long globalIndex,
                                                 Instant timestamp,
                                                 Instant gapTimeoutThreshold) {
        boolean allowGaps = timestamp.isAfter(gapTimeoutThreshold);
        return token == null
                ? GapAwareTrackingToken.newInstance(globalIndex, calculateGaps(globalIndex, allowGaps))
                : token.advanceTo(globalIndex, allowGaps ? maxGapOffset : 0);
    }

    private Collection<Long> calculateGaps(long globalIndex, boolean allowGaps) {
        return allowGaps
                ? LongStream.range(Math.min(lowestGlobalSequence, globalIndex), globalIndex)
                            .boxed()
                            .collect(Collectors.toCollection(TreeSet::new))
                : Collections.emptySortedSet();
    }

    private GenericEventMessage convertToEventMessage(AggregateEventEntry event) {
        return new GenericEventMessage(event.identifier(),
                                       new MessageType(event.type(), event.version()),
                                       event.payload(),
                                       converter.convert(event.metadata(), METADATA_MAP_TYPE_REF.getType()),
                                       event.timestamp()
        ).withConverter(converter);
    }

    private static Context buildTrackedContext(TokenAndEvent tokenAndEvent) {
        AggregateEventEntry entry = tokenAndEvent.event();
        Context context = buildContext(Objects.requireNonNullElse(entry.aggregateIdentifier(), entry.identifier()),
                                       Objects.requireNonNullElse(entry.aggregateSequenceNumber(), 0L),
                                       entry.aggregateType());
        return context.withResource(TrackingToken.RESOURCE_KEY, tokenAndEvent.token);
    }

    private static Context buildContext(String aggregateIdentifier,
                                        Long aggregateSequenceNumber,
                                        @Nullable String aggregateType) {
        Context context = Context.with(LegacyResources.AGGREGATE_IDENTIFIER_KEY, aggregateIdentifier)
                                 .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, aggregateSequenceNumber);
        return aggregateType != null
                ? context.withResource(LegacyResources.AGGREGATE_TYPE_KEY, aggregateType)
                : context;
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken() {
        return queryToken(FIRST_TOKEN_QUERY);
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken() {
        return queryToken(LATEST_TOKEN_QUERY);
    }

    private CompletableFuture<TrackingToken> queryToken(String firstTokenQuery) {
        return entityManagerExecutor(null).apply(entityManager -> new GapAwareTrackingToken(
            entityManager.createQuery(firstTokenQuery, Long.class).getSingleResult(),
            Set.of()
        ));
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at) {
        return entityManagerExecutor(null).apply(entityManager -> new GapAwareTrackingToken(
            entityManager.createQuery(TOKEN_AT_QUERY, Long.class)
                         .setParameter("dateTime", formatInstant(at))
                         .getSingleResult(),
            Set.of()
        ));
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("transactionalExecutorProvider", transactionalExecutorProvider);
        descriptor.describeProperty("converter", converter);
        descriptor.describeProperty("persistenceExceptionResolver", persistenceExceptionResolver);
        descriptor.describeProperty("tokenOperations", tokenOperations);
    }

    /**
     * Releases any resources associated with this engine.
     */
    public void close() {
        if (eventCoordinatorHandle != null) {
            eventCoordinatorHandle.terminate();
            eventCoordinatorHandle = null;
        }
    }

    private void onAppendDetected() {

        /*
         * When doing (unknown) callbacks that could throw exceptions, those may kill essential threads
         * or otherwise interrupt other important code. Normally, you'd protect that by catching exceptions
         * and logging them only, but in this case, ContinuousMessageStream already takes care of that
         * for us. If that ever changes, this code should be updated as we do rely on the fact that it
         * won't throw exceptions.
         */

        for (Runnable callback : streamCallbacks.values()) {
            callback.run();
        }
    }

    private TransactionalExecutor<EntityManager> entityManagerExecutor(ProcessingContext processingContext) {
        return transactionalExecutorProvider.getTransactionalExecutor(processingContext);
    }

    /**
     * A tuple of an {@link AtomicReference} to an {@link AggregateBasedConsistencyMarker} and a {@link MessageStream},
     * used when sourcing events from an aggregate-specific {@link EventCriterion}. This tuple object can then be used
     * to {@link MessageStream#concatWith(MessageStream) construct a single stream}, completing with a final marker.
     */
    private record AggregateSource(
            AtomicReference<AggregateBasedConsistencyMarker> markerReference,
            MessageStream<EventMessage> source
    ) {

    }

    private record TokenAndEvent(GapAwareTrackingToken token, AggregateEventEntry event) {

    }
}
