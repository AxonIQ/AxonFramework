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

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.TerminalEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.opentest4j.TestAbortedException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite validating implementations of {@link EventStorageEngine} implementations that are aggregate-based.
 *
 * @param <ESE> the event storage engine type
 * @author Allard Buijze
 * @since 5.0.0
 */
public abstract class AggregateBasedStorageEngineTestSuite<ESE extends EventStorageEngine> {

    private static final String TEST_AGGREGATE_TYPE = "TEST_AGGREGATE";
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    protected String TEST_AGGREGATE_ID;
    protected String OTHER_AGGREGATE_ID;
    protected Set<Tag> TEST_AGGREGATE_TAGS;
    protected EventCriteria TEST_AGGREGATE_CRITERIA;
    protected Set<Tag> OTHER_AGGREGATE_TAGS;
    protected EventCriteria OTHER_AGGREGATE_CRITERIA;

    protected EventConverter converter;

    protected ESE testSubject;

    @BeforeEach
    void setUp() throws Exception {
        TEST_AGGREGATE_ID = UUID.randomUUID().toString();
        OTHER_AGGREGATE_ID = UUID.randomUUID().toString();

        TEST_AGGREGATE_TAGS = Set.of(new Tag(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID));
        TEST_AGGREGATE_CRITERIA = EventCriteria.havingTags(TEST_AGGREGATE_TYPE, TEST_AGGREGATE_ID);
        OTHER_AGGREGATE_TAGS = Set.of(new Tag("OTHER_AGGREGATE", OTHER_AGGREGATE_ID));
        OTHER_AGGREGATE_CRITERIA = EventCriteria.havingTags("OTHER_AGGREGATE", OTHER_AGGREGATE_ID);

        converter = new DelegatingEventConverter(new JacksonConverter());

        testSubject = buildStorageEngine();
    }

    /**
     * Constructs the {@link EventStorageEngine} used in this test suite.
     *
     * @return The {@link EventStorageEngine} used in this test suite.
     * @throws Exception when building the engine fails
     */
    protected abstract ESE buildStorageEngine() throws Exception;

    /**
     * Returns the processing context to use for event storage engine calls.
     *
     * @return The {@link ProcessingContext}.
     */
    protected abstract ProcessingContext processingContext();

    /**
     * Will translate position to global sequence index. It differs among the EventStore implementations. For example:
     * AxonServer starts the global stream from 0, whereas JPA implementations starts from 1.
     *
     * @param position the event order to translate, first is 1
     * @return the global sequence index for given event storage engine
     */
    protected abstract long globalSequenceOfEvent(long position);

    protected abstract TrackingToken trackingTokenAt(long position);

    @Test
    void streamingFromStartReturnsSelectedMessages() {
        TaggedEventMessage<?> expectedEventOne = taggedEventMessage("event-0", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventTwo = taggedEventMessage("event-1", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventThree = taggedEventMessage("event-4", TEST_AGGREGATE_TAGS);
        // Ensure there are "gaps" in the global stream based on events not matching the condition.
        ConsistencyMarker newMarker = appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                expectedEventOne,
                expectedEventTwo
        );

        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-2", emptySet()),
                taggedEventMessage("event-3", emptySet())
        );

        appendEvents(
                new DefaultAppendCondition(newMarker, TEST_AGGREGATE_CRITERIA),
                expectedEventThree
        );

        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-5", emptySet()),
                taggedEventMessage("event-6", emptySet())
        );

        MessageStream<EventMessage> result =
                testSubject.stream(StreamingCondition.startingFrom(trackingTokenAt(0)));

        StepVerifier.create(FluxUtils.of(result))
                    .assertNext(entry -> assertTrackedEntry(entry, expectedEventOne.event(), 1))
                    .assertNext(entry -> assertTrackedEntry(entry, expectedEventTwo.event(), 2))
                    .expectNextCount(2)
                    .assertNext(entry -> assertTrackedEntry(entry, expectedEventThree.event(), 5))
                    .expectNextCount(2)
                    .thenCancel()
                    .verify();
    }

    @Test
    void streamingFromSpecificPositionSkipsMessages() {
        TaggedEventMessage<?> expectedEventOne = taggedEventMessage("event-0", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventTwo = taggedEventMessage("event-1", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventThree = taggedEventMessage("event-4", TEST_AGGREGATE_TAGS);
        // Ensure there are "gaps" in the global stream based on events not matching the condition.
        appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                expectedEventOne,
                expectedEventTwo,
                taggedEventMessage("event-2", Set.of()),
                taggedEventMessage("event-3", TEST_AGGREGATE_TAGS),
                expectedEventThree,
                taggedEventMessage("event-5", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-6", TEST_AGGREGATE_TAGS)
        );

        MessageStream<EventMessage> result =
                testSubject.stream(StreamingCondition.startingFrom(trackingTokenAt(2)));

        StepVerifier.create(FluxUtils.of(result))
                    // we've skipped the first two
                    .expectNextCount(2).assertNext(entry -> assertTrackedEntry(entry, expectedEventThree.event(), 5))
                    .expectNextCount(2).thenCancel().verify();
    }

    @Test
    void streamingWithTagConditionShouldSkipNonMatchingMessages() {
        TaggedEventMessage<?> expectedEventOne = taggedEventMessage("event-0", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventTwo = taggedEventMessage("event-1", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventThree = taggedEventMessage("event-4", TEST_AGGREGATE_TAGS);
        // Ensure there are "gaps" in the global stream based on events not matching the condition.
        appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                expectedEventOne,
                expectedEventTwo,
                taggedEventMessage("event-2", Set.of()),
                taggedEventMessage("event-3", OTHER_AGGREGATE_TAGS),
                expectedEventThree,
                taggedEventMessage("event-5", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-6", OTHER_AGGREGATE_TAGS)
        );

        MessageStream<EventMessage> result =
                testSubject.stream(
                        StreamingCondition.conditionFor(trackingTokenAt(0),
                                                        EventCriteria.havingTags(TEST_AGGREGATE_TAGS))
                );

        StepVerifier.create(FluxUtils.of(result))
                    .assertNext(entry -> assertTrackedEntry(entry, expectedEventOne.event(), 1))
                    .assertNext(entry -> assertTrackedEntry(entry, expectedEventTwo.event(), 2))
                    .assertNext(entry -> assertTrackedEntry(entry, expectedEventThree.event(), 5))
                    .thenCancel().verify();
    }

    @Test
    void streamingWithTypeConditionShouldSkipNonMatchingMessages() {
        TaggedEventMessage<?> msg1 = taggedEventMessage("event-1", TEST_AGGREGATE_TAGS, "create");
        TaggedEventMessage<?> msg2 = taggedEventMessage("event-2", TEST_AGGREGATE_TAGS, "update");
        TaggedEventMessage<?> msg3 = taggedEventMessage("event-3", Set.of(), "create");
        TaggedEventMessage<?> msg4 = taggedEventMessage("event-4", OTHER_AGGREGATE_TAGS, "update");
        TaggedEventMessage<?> msg5 = taggedEventMessage("event-5", TEST_AGGREGATE_TAGS, "update");
        TaggedEventMessage<?> msg6 = taggedEventMessage("event-6", OTHER_AGGREGATE_TAGS, "update");
        TaggedEventMessage<?> msg7 = taggedEventMessage("event-7", OTHER_AGGREGATE_TAGS, "delete");

        appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                msg1, msg2, msg3, msg4, msg5, msg6, msg7
        );

        MessageStream<EventMessage> result =
                testSubject.stream(
                        StreamingCondition.conditionFor(trackingTokenAt(0),
                                                        EventCriteria.havingAnyTag()
                                                                     .andBeingOneOfTypes("update", "delete"))
                );

        StepVerifier.create(FluxUtils.of(result))
                    .assertNext(entry -> assertTrackedEntry(entry, msg2.event(), 2))
                    .assertNext(entry -> assertTrackedEntry(entry, msg4.event(), 4))
                    .assertNext(entry -> assertTrackedEntry(entry, msg5.event(), 5))
                    .assertNext(entry -> assertTrackedEntry(entry, msg6.event(), 6))
                    .assertNext(entry -> assertTrackedEntry(entry, msg7.event(), 7))
                    .thenCancel().verify();
    }

    @Test
    void streamingAfterLastPositionReturnsEmptyStream() {
        EventCriteria expectedCriteria = TEST_AGGREGATE_CRITERIA;
        TaggedEventMessage<?> expectedEventOne = taggedEventMessage("event-0", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventTwo = taggedEventMessage("event-1", TEST_AGGREGATE_TAGS);
        TaggedEventMessage<?> expectedEventThree = taggedEventMessage("event-4", TEST_AGGREGATE_TAGS);
        // Ensure there are "gaps" in the global stream based on events not matching the condition.
        ConsistencyMarker marker1 = appendEvents(
                AppendCondition.withCriteria(expectedCriteria),
                expectedEventOne,
                expectedEventTwo
        );

        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-2", Set.of()),
                taggedEventMessage("event-3", Set.of())
        );

        appendEvents(
                new DefaultAppendCondition(marker1, expectedCriteria),
                expectedEventThree
        );

        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-5", Set.of()),
                taggedEventMessage("event-6", Set.of())
        );

        MessageStream<EventMessage> result = testSubject.stream(StreamingCondition.startingFrom(
                trackingTokenAt(10)).or(expectedCriteria));

        try {
            assertTrue(result.next().isEmpty());
        } finally {
            result.close();
        }
    }

    @Test
    void sourcingEventsReturnsMatchingAggregateEventAndConsistencyMarkerEntry() {
        AppendCondition appendCondition = AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA);

        appendEvents(
                appendCondition,
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS)
        );

        StepVerifier.create(FluxUtils.of(testSubject.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))))
                    .expectNextMatches(entryWithAggregateEvent("event-0", 0))
                    .expectNextMatches(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
    }

    @Test
    void sourcingEventsWithMetadata() {
        AppendCondition appendCondition = AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA);

        appendEvents(
                appendCondition,
                taggedEventMessage(
                        "event-0",
                        TEST_AGGREGATE_TAGS,
                        Metadata.with("key1", "value1")
                                .and("key2", "true")
                                .and("key3", "44")
                )
        );

        StepVerifier.create(FluxUtils.of(testSubject.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))))
                    .expectNextMatches(entryWithAggregateEvent("event-0", 0))
                    .expectNextMatches(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
    }

    @Test
    void sourcingEventsReturnsMatchingAggregateEvents() {
        AppendCondition appendCondition = AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA);
        AppendCondition appendCondition2 = AppendCondition.withCriteria(OTHER_AGGREGATE_CRITERIA);

        appendEvents(
                appendCondition,
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-1", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-2", TEST_AGGREGATE_TAGS)
        );

        appendEvents(
                appendCondition2,
                taggedEventMessage("event-4", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-5", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-6", OTHER_AGGREGATE_TAGS)
        );

        StepVerifier.create(FluxUtils.of(testSubject.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))))
                    .expectNextMatches(entryWithAggregateEvent("event-0", 0))
                    .expectNextMatches(entryWithAggregateEvent("event-1", 1))
                    .expectNextMatches(entryWithAggregateEvent("event-2", 2))
                    .expectNextMatches(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
    }

    @Test
    void eventsWithoutTagsAreNotSourcedAsAggregatedEvents() {
        appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                taggedEventMessage("event-0", Set.of()),
                taggedEventMessage("event-1", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-2", Set.of()),
                taggedEventMessage("event-3", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-4", Set.of())
        );

        StepVerifier.create(FluxUtils.of(testSubject.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))))
                    .expectNextMatches(entryWithAggregateEvent("event-1", 0))
                    .expectNextMatches(entryWithAggregateEvent("event-3", 1))
                    .expectNextMatches(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
    }

    @Test
    void eventsWithTagsNotMatchingCriteriaAreInsertedAtSequenceZero() {
        appendEvents(
                AppendCondition.withCriteria(OTHER_AGGREGATE_CRITERIA),
                taggedEventMessage("event-4", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-5", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-6", TEST_AGGREGATE_TAGS)
        );

        StepVerifier.create(FluxUtils.of(testSubject.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))))
                    .expectNextMatches(entryWithAggregateEvent("event-4", 0))
                    .expectNextMatches(entryWithAggregateEvent("event-5", 1))
                    .expectNextMatches(entryWithAggregateEvent("event-6", 2))
                    .expectNextMatches(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
    }

    @Test
    void sourcingFromTwoAggregateStreamsReturnsACombinedStream() {
        var appendMarker = appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-1", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-2", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-3", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-4", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-5", OTHER_AGGREGATE_TAGS)
        );

        SourcingCondition testCondition =
                SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA.or(OTHER_AGGREGATE_CRITERIA));

        StepVerifier.create(FluxUtils.of(testSubject.source(testCondition)))
                    .expectNextCount(6)
                    .assertNext(entry -> assertEquals(appendMarker, entry.getResource(ConsistencyMarker.RESOURCE_KEY)))
                    .verifyComplete();
    }

    @Test
    void sourcingBeginsEventStreamFromTheSpecifiedPosition() {
        // given...
        Set<String> expected = Set.of("event-2", "event-4");
        Set<String> actual = new HashSet<>();

        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-1", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-2", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-3", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-4", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-5", OTHER_AGGREGATE_TAGS)
        );

        // when...
        SourcingCondition testCondition = SourcingCondition.conditionFor(new AggregateSequenceNumberPosition(1),
                                                                         TEST_AGGREGATE_CRITERIA);
        MessageStream<EventMessage> result = testSubject.source(testCondition);
        // then...
        StepVerifier.create(FluxUtils.of(result))
                    .consumeNextWith(entry -> actual.add(entry.message().payloadAs(String.class)))
                    .consumeNextWith(entry -> actual.add(entry.message().payloadAs(String.class)))
                    .assertNext(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
        assertEquals(expected, actual);
    }

    @Test
    void sourcingFromTwoAggregatesBeginsEventStreamsFromTheSpecifiedPositions() {
        // given...
        Set<String> expected = Set.of("event-2", "event-3", "event-4", "event-5");
        Set<String> actual = new HashSet<>();

        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-1", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-2", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-3", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-4", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-5", OTHER_AGGREGATE_TAGS)
        );

        // when...
        MessageStream<EventMessage> source;
        SourcingCondition testCondition =
                SourcingCondition.conditionFor(new AggregateSequenceNumberPosition(1),
                                               TEST_AGGREGATE_CRITERIA.or(OTHER_AGGREGATE_CRITERIA));
        try {
            source = testSubject.source(testCondition);
        } catch (IllegalArgumentException e) {
            throw new TestAbortedException("Multi-aggregate streams not supported", e);
        }

        // then...
        StepVerifier.create(FluxUtils.of(source))
                    .consumeNextWith(entry -> actual.add(entry.message().payloadAs(String.class)))
                    .consumeNextWith(entry -> actual.add(entry.message().payloadAs(String.class)))
                    .consumeNextWith(entry -> actual.add(entry.message().payloadAs(String.class)))
                    .consumeNextWith(entry -> actual.add(entry.message().payloadAs(String.class)))
                    .assertNext(AggregateBasedStorageEngineTestSuite::assertMarkerEntry)
                    .verifyComplete();
        assertEquals(expected, actual);
    }

    @Test
    void sourcingAnEmptyEventStoreReturnsAnExpectedConsistencyMarker() {
        // given...
        ConsistencyMarker testAggregateMarker = new AggregateBasedConsistencyMarker(TEST_AGGREGATE_ID, -1);
        ConsistencyMarker otherAggregateMarker = new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, -1);
        ConsistencyMarker expectedMarker = testAggregateMarker.upperBound(otherAggregateMarker);
        // when...
        SourcingCondition testCondition =
                SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA.or(OTHER_AGGREGATE_CRITERIA));
        MessageStream<EventMessage> result = testSubject.source(testCondition);
        // then...
        StepVerifier.create(FluxUtils.of(result))
                    .assertNext(entry -> assertEquals(
                            expectedMarker, entry.getResource(ConsistencyMarker.RESOURCE_KEY)
                    ))
                    .verifyComplete();
    }

    /**
     * Regression test for <a href="https://github.com/AxonIQ/AxonFramework/issues/4437">#4437</a>
     */
    @Test
    void appendingFirstEventAfterSourcingEmptyStreamSucceeds() {
        // given
        SourcingCondition sourcingCondition = SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA);
        AtomicReference<ConsistencyMarker> markerFromSource = new AtomicReference<>();
        StepVerifier.create(FluxUtils.of(testSubject.source(sourcingCondition)))
                    .assertNext(entry -> markerFromSource.set(entry.getResource(ConsistencyMarker.RESOURCE_KEY)))
                    .verifyComplete();

        // when
        AppendCondition appendCondition = AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA)
                                                         .withMarker(markerFromSource.get());
        assertDoesNotThrow(
                () -> appendEvents(appendCondition, taggedEventMessage("event-0", TEST_AGGREGATE_TAGS))
        );
    }


    @Test
    void transactionRejectedWithConflictingEventsInStore() {
        ConsistencyMarker consistencyMarker = appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS)
        );

        appendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA).withMarker(consistencyMarker),
                taggedEventMessage("event-1", TEST_AGGREGATE_TAGS)
        );

        CompletableFuture<ConsistencyMarker> actual = asyncAppendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA).withMarker(consistencyMarker),
                taggedEventMessage("event-2", TEST_AGGREGATE_TAGS)
        );

        ExecutionException actualException = assertThrows(ExecutionException.class,
                                                          () -> actual.get(1, TimeUnit.SECONDS));
        assertInstanceOf(AppendEventsTransactionRejectedException.class, actualException.getCause());
    }

    @Test
    void transactionRejectedWhenConcurrentlyCreatedTransactionIsCommittedFirst() {
        ProcessingContext pc1 = processingContext();
        ProcessingContext pc2 = processingContext();

        var firstTx = testSubject.appendEvents(AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                                               pc1,
                                               taggedEventMessage("event-10", TEST_AGGREGATE_TAGS));
        var secondTx = testSubject.appendEvents(AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                                                pc2,
                                                taggedEventMessage("event-11", TEST_AGGREGATE_TAGS));

        CompletableFuture<ConsistencyMarker> firstCommit = finishTx(firstTx, pc1);
        assertDoesNotThrow(() -> firstCommit.get(5, TimeUnit.SECONDS));

        CompletableFuture<ConsistencyMarker> secondCommit = finishTx(secondTx, pc2);
        var thrown = assertThrows(ExecutionException.class, () -> secondCommit.get(5, TimeUnit.SECONDS));
        assertInstanceOf(AppendEventsTransactionRejectedException.class, thrown.getCause());
    }

    @Test
    void whenConflictingTransactionsRunOnDifferentThreadsConcurrentlyThenOnlyOneOfThemIsCommited() {
        List<CompletableFuture<ConsistencyMarker>> transactions = List.of(
                runAsync(() -> asyncAppendEvents(
                        AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                        taggedEventMessage("event-10", TEST_AGGREGATE_TAGS)
                )),
                runAsync(() -> asyncAppendEvents(
                        AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                        taggedEventMessage("event-11", TEST_AGGREGATE_TAGS)
                )),
                runAsync(() -> asyncAppendEvents(
                        AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                        taggedEventMessage("event-12", TEST_AGGREGATE_TAGS)
                )),
                runAsync(() -> asyncAppendEvents(
                        AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                        taggedEventMessage("event-13", TEST_AGGREGATE_TAGS)
                ))
        );

        CompletableFuture<?> result = CompletableFuture.allOf(transactions.toArray(CompletableFuture[]::new));
        var thrown = assertThrows(Exception.class, result::join);
        assertInstanceOf(AppendEventsTransactionRejectedException.class, thrown.getCause());

        var commitedTransaction = transactions.stream().filter(tx -> !tx.isCompletedExceptionally()).count();
        assertEquals(1, commitedTransaction);
        var rejectedTransactions = transactions.stream().filter(CompletableFuture::isCompletedExceptionally).count();
        assertEquals(transactions.size() - 1, rejectedTransactions);
    }

    private static <T> CompletableFuture<T> runAsync(Supplier<CompletableFuture<T>> task) {
        return CompletableFuture.supplyAsync(task, EXECUTOR).thenCompose(future -> future);
    }

    @Test
    void concurrentTransactionsForNonOverlappingTagsBothCommit() {
        CompletableFuture<ConsistencyMarker> tx1 = CompletableFuture.supplyAsync(() -> asyncAppendEvents(
                AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA),
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS)
        )).thenCompose(f -> f);
        CompletableFuture<ConsistencyMarker> tx2 = CompletableFuture.supplyAsync(() -> asyncAppendEvents(
                AppendCondition.withCriteria(OTHER_AGGREGATE_CRITERIA),
                taggedEventMessage("event-0", OTHER_AGGREGATE_TAGS)
        )).thenCompose(f -> f);

        ConsistencyMarker marker1 = tx1.join();
        ConsistencyMarker marker2 = tx2.join();

        assertThat(AggregateSequenceNumberPosition.toSequenceNumber(marker1.position())).isEqualTo(1);
        assertThat(AggregateSequenceNumberPosition.toSequenceNumber(marker2.position())).isEqualTo(1);
    }

    @Test
    void emptyTransactionAlwaysCommitSuccessfullyAndReturnsOriginConsistencyMarker() {
        var appendCondition = AppendCondition.withCriteria(TEST_AGGREGATE_CRITERIA);

        var commit = asyncAppendEvents(appendCondition);

        var afterCommitConsistencyMarker = assertDoesNotThrow(commit::join);
        assertEquals(ConsistencyMarker.ORIGIN, afterCommitConsistencyMarker);
    }

    @Test
    void eventWithMultipleTagsIsReportedAsPartOfException() {
        TaggedEventMessage<?> violatingEntry = taggedEventMessage("event2",
                                                                  Set.of(new Tag("key1", "value1"),
                                                                         new Tag("key2", "value2")));
        CompletableFuture<EventStorageEngine.AppendTransaction<?>> actual = testSubject.appendEvents(
                AppendCondition.none(),
                processingContext(),
                taggedEventMessage("event1", Set.of(new Tag("key1", "value1"))),
                violatingEntry,
                taggedEventMessage("event3", Set.of(new Tag("key1", "value1")))
        );

        assertTrue(actual.isDone());
        assertTrue(actual.isCompletedExceptionally());

        ExecutionException actualException = assertThrows(ExecutionException.class, actual::get);
        if (actualException.getCause() instanceof TooManyTagsOnEventMessageException e) {
            assertEquals(violatingEntry.tags(), e.tags());
            assertEquals(violatingEntry.event(), e.eventMessage());
        } else {
            fail("Unexpected exception", actualException);
        }
    }

    @Test
    void firstTokenShouldReturnNonNullForEmptyStore() throws InterruptedException, ExecutionException {
        assertThat(testSubject.firstToken().get()).isNotNull();
    }

    @Test
    void latestTokenShouldReturnNonNullForEmptyStore() throws InterruptedException, ExecutionException {
        assertThat(testSubject.latestToken().get()).isNotNull();
    }

    @Test
    void firstTokenAndLatestTokenShouldBeEqualForEmptyStore() throws InterruptedException, ExecutionException {
        assertThat(testSubject.latestToken().get())
                .isEqualTo(testSubject.firstToken().get());
    }

    @Test
    void tokenAtShouldReturnNonNullForEmptyStore() throws InterruptedException, ExecutionException {
        assertThat(testSubject.tokenAt(Instant.now()).get()).isNotNull();
    }

    @Test
    void tokenAtShouldReturnMaxTokenForFutureTimestamp() throws InterruptedException, ExecutionException {
        appendEvents(
                AppendCondition.none(),
                taggedEventMessage("event-0", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-1", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-2", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-3", OTHER_AGGREGATE_TAGS),
                taggedEventMessage("event-4", TEST_AGGREGATE_TAGS),
                taggedEventMessage("event-5", OTHER_AGGREGATE_TAGS)
        );

        assertThat(testSubject.tokenAt(Instant.now().plusSeconds(1000)).get())
                .isEqualTo(trackingTokenAt(6));
    }

    @Test
    void callbackShouldOnlyBeCalledOnceButNotWhenNoNewEntriesAreAvailable() {
        // Given - stream with single entry and boolean callback
        AtomicBoolean called = new AtomicBoolean(false);
        final MessageStream<EventMessage> stream = testSubject.source(SourcingCondition.conditionFor(EventCriteria.havingTags(
                "unknown",
                "non-existing")));

        // When - callback is set and there are entries available
        stream.setCallback(() -> {
            // on invocation: consume available entries
            while (stream.hasNextAvailable()) {
                stream.next();
            }
            // mark execution as done
            called.set(true);
        });

        // Then - callback is called and stream is marked as completed
        await().untilAsserted(() -> assertThat(stream.isCompleted()).isTrue());
        await().untilAsserted(() -> assertThat(stream.hasNextAvailable()).isFalse());
        await().untilAsserted(() -> assertThat(called).isTrue());

        // When - execution is reset
        called.set(false);

        // And - we try to consume the stream again
        assertThat(stream.next()).isEmpty();
        // Then - callback is not invoked again, as no new messages arrived
        await().untilAsserted(() -> assertThat(called).isFalse());
    }

    private void assertTrackedEntry(Entry<EventMessage> actual, EventMessage expected, long eventNumber) {
        Optional<TrackingToken> actualToken = TrackingToken.fromContext(actual);
        assertTrue(actualToken.isPresent());
        OptionalLong actualPosition = actualToken.get().position();
        assertTrue(actualPosition.isPresent());
        assertEquals(globalSequenceOfEvent(eventNumber), actualPosition.getAsLong());
        assertEvent(actual.message(), expected);
    }

    private void assertEvent(EventMessage actual, EventMessage expected) {
        assertEquals(expected.payload(), actual.payloadAs(String.class));
        assertEquals(expected.identifier(), actual.identifier());
        assertEquals(expected.timestamp().toEpochMilli(), actual.timestamp().toEpochMilli());
        assertEquals(expected.metadata(), actual.metadata());
    }

    private static boolean assertMarkerEntry(Entry<EventMessage> entry) {
        return entry.getResource(ConsistencyMarker.RESOURCE_KEY) instanceof AggregateBasedConsistencyMarker
                && entry.message().equals(TerminalEventMessage.INSTANCE);
    }

    protected static TaggedEventMessage<?> taggedEventMessage(String payload, Set<Tag> tags) {
        return taggedEventMessage(payload, tags, Metadata.emptyInstance());
    }

    protected static TaggedEventMessage<?> taggedEventMessage(String payload, Set<Tag> tags, String messageType) {
        return taggedEventMessage(payload, tags, messageType, Metadata.emptyInstance());
    }

    protected static TaggedEventMessage<?> taggedEventMessage(String payload, Set<Tag> tags, Metadata metadata) {
        return taggedEventMessage(payload, tags, "event", metadata);
    }

    protected static TaggedEventMessage<?> taggedEventMessage(String payload, Set<Tag> tags, String messageType,
                                                              Metadata metadata) {
        return new GenericTaggedEventMessage<>(
                new GenericEventMessage(new MessageType(messageType), payload, metadata),
                tags
        );
    }

    private @NonNull Predicate<Entry<EventMessage>> entryWithAggregateEvent(String expectedPayload,
                                                                            int expectedSequence) {
        return e -> expectedPayload.equals(e.message().payloadAs(String.class))
                && TEST_AGGREGATE_ID.equals(e.getResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY))
                && e.getResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY) == expectedSequence
                && TEST_AGGREGATE_TYPE.equals(e.getResource(LegacyResources.AGGREGATE_TYPE_KEY));
    }

    protected ConsistencyMarker appendEvents(AppendCondition condition, TaggedEventMessage<?>... events) {
        ProcessingContext processingContext = processingContext();

        return finishTx(testSubject.appendEvents(condition, processingContext, events), processingContext).join();
    }

    protected CompletableFuture<ConsistencyMarker> asyncAppendEvents(AppendCondition condition,
                                                                     TaggedEventMessage<?>... events) {
        ProcessingContext processingContext = processingContext();

        return finishTx(testSubject.appendEvents(condition, processingContext, events), processingContext);
    }

    protected CompletableFuture<ConsistencyMarker> finishTx(CompletableFuture<AppendTransaction<?>> future,
                                                            ProcessingContext processingContext) {
        return future
                .thenApply(this::castTransaction)
                .thenCompose(tx -> tx.commit()
                                     .thenCompose(r -> tx.afterCommit(r))
                );
    }

    @SuppressWarnings("unchecked")
    protected final AppendTransaction<Object> castTransaction(AppendTransaction<?> at) {
        return (AppendTransaction<Object>) at;
    }

    public record ComplexObject(String value1, boolean value2, int value3) {

    }
}