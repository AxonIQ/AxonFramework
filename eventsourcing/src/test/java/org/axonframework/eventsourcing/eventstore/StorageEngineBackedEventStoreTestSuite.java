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

import org.assertj.core.api.AbstractIterableAssert;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for the {@link StorageEngineBackedEventStore} which can be parameterized
 * with an {@link EventStorageEngine}. The suite uses the full processing lifecycle
 * and units of work to do all its test.
 * <p>
 * All test in this suite are {@code protected} to make it possible to (temporarily)
 * disable some tests for a specific engine. All helper methods in this suite are also
 * {@code protected} to make it easier to write additional engine specific tests.
 * <p>
 * Note that this suite expects that the engine used it only created once. This means
 * events from previous tests may be present in the store. The tests therefore use
 * a base token and base time for all tests. Classes which implement this test suite
 * that wish to include additional test cases should use these to ensure the tests
 * are independent of each other.
 *
 * @author John Hendrikx
 * @param <E> the event storage engine type
 */
public abstract class StorageEngineBackedEventStoreTestSuite<E extends EventStorageEngine> {

    /**
     * A {@link Comparator} for event messages. This can be used with AssertJ's
     * {@link AbstractIterableAssert#usingComparatorForType(Comparator, Class)} to compare
     * lists of events.
     */
    protected static final Comparator<GenericEventMessage> EVENT_COMPARATOR = Comparator
        .comparing(GenericEventMessage::payload, Comparator.comparing(Object::toString))
        .thenComparing(GenericEventMessage::timestamp)
        .thenComparing(GenericEventMessage::identifier)
        .thenComparing(GenericEventMessage::metadata, Comparator.comparing(Object::toString));

    private static final EventConverter CONVERTER = new DelegatingEventConverter(new JacksonConverter());
    protected static final AnnotationMessageTypeResolver RESOLVER = new AnnotationMessageTypeResolver();

    private static Instant nextEventTimestamp = Instant.EPOCH;  // tracks auto time incrementing for created events
    private static long messageId;

    /*
     * Potential bug fix for a hanging test during CI. This executor ensures
     * it has sufficient threads to run tests that rely on parallelism to
     * execute. Locally, the CI problem was reproducable by using a single
     * thread pool.
     */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(5);

    /**
     * A tag for some of the messages inserted by the tests. Created
     * anew for each test as the event store may not be reset between
     * tests.
     */
    protected Tag TAG1 = new Tag("Course", UUID.randomUUID().toString());

    /**
     * A tag for some of the messages inserted by the tests. Created
     * anew for each test as the event store may not be reset between
     * tests.
     */
    protected Tag TAG2 = new Tag("Course", UUID.randomUUID().toString());

    /**
     * The {@link EventStorageEngine} that was created.
     */
    protected E engine;

    /**
     * Contains the token of the first event of this test run. As a new event store
     * is not created per test, use this to ignore events from previous tests.
     */
    protected TrackingToken baseToken;

    /**
     * Contains the time of the first event created for this test run. As a new event
     * store is not created per test, use this to ignore events from previous tests.
     */
    protected Instant baseTime;

    protected StorageEngineBackedEventStore eventStore;

    @BeforeEach
    void beforeEach() throws Exception {
        engine = getStorageEngine(CONVERTER);  // expected to be the same for each test
        eventStore = new StorageEngineBackedEventStore(
            engine,
            new SimpleEventBus(),
            new AnnotationBasedTagResolver()
        );

        /*
         * There is only one event storage engine ever created; in order for tests to
         * share the same underlying store without interference, each test gets its
         * own baseToken and baseTime. The baseTime is incremented one day for each test.
         */

        baseTime = nextEventTimestamp.plusSeconds(24 * 60 * 60).truncatedTo(ChronoUnit.DAYS);
        baseToken = unitOfWork().executeWithResult(eventStore::latestToken).join();
        nextEventTimestamp = baseTime;
    }

    @AfterAll
    static void afterAll() {
        EXECUTOR.shutdown();
    }

    /**
     * Constructs the {@link EventStorageEngine} used in this test suite.
     *
     * @param converter The converter to use, cannot be {@code null}.
     * @return The {@link EventStorageEngine} used in this test suite.
     * @throws Exception when getting the engine fails
     */
    protected abstract @NonNull E getStorageEngine(@NonNull EventConverter converter) throws Exception;

    /**
     * Creates a {@link UnitOfWork} with its transactional resource configured.
     *
     * @return A {@link UnitOfWork}.
     */
    protected abstract @NonNull UnitOfWork unitOfWork();

    @Nested
    protected class GivenSomePublishedEvents {
        protected EventMessage event1;
        protected EventMessage event2;
        protected EventMessage event3;
        protected EventMessage event4;
        protected EventMessage event5;

        @BeforeEach
        void beforeEach() {
            this.event1 = message(new CourseUpdated(TAG1.value(), "1"));
            this.event2 = message(new CourseUpdated(TAG1.value(), "2"));
            this.event3 = message(new CourseUpdated(TAG2.value(), "A"));
            this.event4 = message(new CourseUpdated(TAG2.value(), "B"));
            this.event5 = message(new CourseUpdated(TAG1.value(), "3"));

            unitOfWork().executeWithResult(pc -> eventStore.publish(pc, event1, event2, event3, event4, event5)).join();
        }

        @Test
        protected void latestTokenShouldCoverFirstToken() {
            TrackingToken firstToken = unitOfWork().executeWithResult(eventStore::firstToken).join();
            TrackingToken latestToken = unitOfWork().executeWithResult(eventStore::latestToken).join();

            assertThat(latestToken.covers(firstToken)).isTrue();
            assertThat(firstToken.covers(latestToken)).isFalse();
        }

        @Test
        protected void tokenAtShouldReturnValidTokens() {
            TrackingToken ancient = unitOfWork().executeWithResult(pc -> eventStore.tokenAt(baseTime.plusSeconds(-1000), pc)).join();
            TrackingToken old = unitOfWork().executeWithResult(pc -> eventStore.tokenAt(baseTime.plusSeconds(5), pc)).join();
            TrackingToken recent = unitOfWork().executeWithResult(pc -> eventStore.tokenAt(baseTime.plusSeconds(15), pc)).join();
            TrackingToken future = unitOfWork().executeWithResult(pc -> eventStore.tokenAt(baseTime.plusSeconds(1000), pc)).join();

            assertThat(ancient.covers(ancient)).isTrue();
            assertThat(ancient.covers(old)).isFalse();
            assertThat(ancient.covers(recent)).isFalse();
            assertThat(ancient.covers(future)).isFalse();

            assertThat(old.covers(ancient)).isTrue();
            assertThat(old.covers(old)).isTrue();
            assertThat(old.covers(recent)).isFalse();
            assertThat(old.covers(future)).isFalse();

            assertThat(recent.covers(ancient)).isTrue();
            assertThat(recent.covers(old)).isTrue();
            assertThat(recent.covers(recent)).isTrue();
            assertThat(recent.covers(future)).isFalse();

            assertThat(future.covers(ancient)).isTrue();
            assertThat(future.covers(old)).isTrue();
            assertThat(future.covers(recent)).isTrue();
            assertThat(future.covers(future)).isTrue();
        }

        @Test
        @Disabled("#3997 - When queried with tokenAt('1970-01-06T23:58:20Z') both Axon Server and in-memory return a token that includes a far older event: '1970-01-06T00:00:20Z'")
        // TODO #3997 - it looks like they return a token that's one position too low?  Postgres does not do this
        protected void shouldStreamSameEventsWithSimilarStartingPositions() {
            TrackingToken ancient = unitOfWork().executeWithResult(pc -> eventStore.tokenAt(baseTime.minusSeconds(100), pc)).join();
            TrackingToken recent = unitOfWork().executeWithResult(pc -> eventStore.tokenAt(baseTime.plusSeconds(15), pc)).join();

            List<EventMessage> allEvents = stream(StreamingCondition.startingFrom(baseToken), 5);

            assertThat(allEvents)
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactlyElementsOf(stream(StreamingCondition.startingFrom(ancient), 5));

            assertThat(allEvents.subList(3, 5))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactlyElementsOf(stream(StreamingCondition.startingFrom(recent), 2));
        }

        @Test
        protected void sourcingShouldOnlyReturnEventsMatchingCriteria() throws Exception {
            assertThat(source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1))))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event1, event2, event5);

            assertThat(source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG2))))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event3, event4);
        }

        @Test
        protected void streamingShouldOnlyReturnEventsMatchingCriteria() throws Exception {
            assertThat(stream(StreamingCondition.conditionFor(baseToken, EventCriteria.havingTags(TAG1)), 3))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event1, event2, event5);

            assertThat(stream(StreamingCondition.conditionFor(baseToken, EventCriteria.havingTags(TAG2)), 2))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event3, event4);
        }

        @Test
        protected void shouldAppendEventAfterSourcing() {
            UnitOfWork uow = unitOfWork();
            EventMessage newMessage = message(new CourseUpdated(TAG1.value(), "4"));

            uow.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                tx.appendEvent(newMessage);
            });

            execute(uow);

            assertThat(stream(StreamingCondition.conditionFor(baseToken, EventCriteria.havingTags(TAG1)), 4))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event1, event2, event5, newMessage);
        }

        @Test
        protected void shouldAppendMultipleEventsAfterSourcing() {
            UnitOfWork uow = unitOfWork();
            EventMessage newMessage1 = message(new CourseUpdated(TAG1.value(), "4"));
            EventMessage newMessage2 = message(new CourseUpdated(TAG1.value(), "5"));

            uow.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                tx.appendEvent(newMessage1);
                tx.appendEvent(newMessage2);
            });

            execute(uow);

            assertThat(stream(StreamingCondition.conditionFor(baseToken, EventCriteria.havingTags(TAG1)), 5))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event1, event2, event5, newMessage1, newMessage2);
        }

        @Test
        protected void latestTokenShouldSeeOnlyNewEvents() {
            TrackingToken latestToken = unitOfWork().executeWithResult(eventStore::latestToken).join();

            // Append a message:
            EventMessage newMessage = message(new CourseUpdated(TAG1.value(), "4"));
            UnitOfWork uow = unitOfWork();

            uow.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                tx.appendEvent(newMessage);
            });

            execute(uow);

            assertThat(stream(StreamingCondition.startingFrom(latestToken), 1))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(newMessage);
        }

        @Test
        protected void consistencyMarkerPositionFromAppendEventShouldSeeOnlyNewEvents() {
            EventMessage newMessage1 = message(new CourseUpdated(TAG1.value(), "4"));
            EventMessage newMessage2 = message(new CourseUpdated(TAG1.value(), "5"));
            AtomicReference<EventStoreTransaction> tx1 = new AtomicReference<>();
            AtomicReference<EventStoreTransaction> tx2 = new AtomicReference<>();
            UnitOfWork uow1 = unitOfWork();
            UnitOfWork uow2 = unitOfWork();

            uow1.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);

                tx1.set(tx);

                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                tx.appendEvent(newMessage1);
            });

            execute(uow1);

            ConsistencyMarker marker1 = tx1.get().appendPosition();

            assertThat(source(SourcingCondition.conditionFor(marker1.position(), EventCriteria.havingTags(TAG1))))
                .isEmpty();

            uow2.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);

                tx2.set(tx);

                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(4);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                tx.appendEvent(newMessage2);
            });

            execute(uow2);

            ConsistencyMarker marker2 = tx2.get().appendPosition();

            assertThat(source(SourcingCondition.conditionFor(marker1.position(), EventCriteria.havingTags(TAG1))))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(newMessage2);

            assertThat(source(SourcingCondition.conditionFor(marker2.position(), EventCriteria.havingTags(TAG1))))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .isEmpty();
        }

        @Test
        protected void resumePositionAfterSourcingShouldBeUsableForResumingSourcing() {
            // Obtain a resume position from a sourcing:
            Position resumePosition = unitOfWork().executeWithResult(pc -> {
                AtomicReference<Position> ref = new AtomicReference<>();

                MessageStream<? extends EventMessage> sourcing = eventStore.transaction(pc).source(
                    SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)),
                    ref::set
                );

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);

                return CompletableFuture.completedFuture(ref.get());
            }).join();

            // Expect that sourcing with the resume position returns nothing:
            List<EventMessage> result = source(SourcingCondition.conditionFor(resumePosition, EventCriteria.havingTags(TAG1)));

            assertThat(result).isEmpty();

            // Append a message that would match:
            EventMessage newMessage = message(new CourseUpdated(TAG1.value(), "4"));
            UnitOfWork uow = unitOfWork();

            uow.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                tx.appendEvent(newMessage);
            });

            execute(uow);

            // Expect that sourcing with the resume position now returns only this new message:
            result = source(SourcingCondition.conditionFor(resumePosition, EventCriteria.havingTags(TAG1)));

            assertThat(result).hasSize(1);
            assertThat(result)
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(newMessage);
        }

        @Test
        protected void shouldDetectConflictWhenAppendingWithSameConsistencyMarker() {
            UnitOfWork uow1 = unitOfWork();
            UnitOfWork uow2 = unitOfWork();
            EventMessage newMessage1 = message(new CourseUpdated(TAG1.value(), "4a"));
            EventMessage newMessage2 = message(new CourseUpdated(TAG1.value(), "4b"));

            CountDownLatch sourcingFinished = new CountDownLatch(2);
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);

            /*
             * Transaction 1:
             */

            uow1.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                sourcingFinished.countDown();
                awaitLatch(latch1);

                tx.appendEvent(newMessage1);
            });

            /*
             * Transaction 2:
             */

            uow2.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                sourcingFinished.countDown();
                awaitLatch(latch2);

                tx.appendEvent(newMessage2);
            });

            /*
             * Start both units of work, and wait until they finished sourcing:
             */

            CompletableFuture<Void> execute1 = CompletableFuture.runAsync(() -> execute(uow1), EXECUTOR);
            CompletableFuture<Void> execute2 = CompletableFuture.runAsync(() -> execute(uow2), EXECUTOR);

            awaitLatch(sourcingFinished);

            /*
             * Unblock transaction 2 and verify it commits succesfully:
             */

            latch2.countDown();

            assertDoesNotThrow(() -> execute2.join());

            /*
             * Unblock transaction 1 and verify there is a conflict:
             */

            latch1.countDown();

            assertThatThrownBy(() -> execute1.join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(AppendEventsTransactionRejectedException.class);

            /*
             * Verify that the store only contains the message from Transaction 2:
             */

            assertThat(stream(StreamingCondition.conditionFor(baseToken, EventCriteria.havingTags(TAG1)), 4))
                .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                .containsExactly(event1, event2, event5, newMessage2);
        }

        @Nested
        protected class AndActivelyStreamingEvents {
            protected List<EventMessage> seenEvents = new ArrayList<>();
            protected MessageStream<EventMessage> stream;

            @BeforeEach
            void beforeEach() {
                this.stream = eventStore.open(
                    StreamingCondition.conditionFor(baseToken, EventCriteria.havingTags(TAG1)),
                    null  // processing context makes no sense for streams
                );

                stream.setCallback(() -> Thread.ofVirtual().start(() -> {
                    synchronized(seenEvents) {
                        int lastSize = -1;

                        while(seenEvents.size() != lastSize) {
                            lastSize = seenEvents.size();
                            stream.next()
                                .map(Entry::message)
                                .map(m -> m.withConvertedPayload(CourseUpdated.class, CONVERTER))
                                .ifPresent(seenEvents::add);
                        }
                    }
                }));

                // wait before starting further tests until all base events are present:
                await().untilAsserted(() -> assertThat(seenEvents)
                    .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                    .containsExactly(event1, event2, event5)
                );
            }

            @AfterEach
            void afterEach() {
                stream.close();
            }

            @Test
            protected void shouldSeeNewMessageWhenAppended() {
                UnitOfWork workUnit = unitOfWork();
                EventMessage newMessage = message(new CourseUpdated(TAG1.value(), "4"));

                workUnit.runOnInvocation(pc -> {
                    EventStoreTransaction tx = eventStore.transaction(pc);
                    MessageStream<? extends EventMessage> sourcing = tx.source(SourcingCondition.conditionFor(EventCriteria.havingTags(TAG1)));

                    assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(3);
                    assertThat(tx.appendPosition()).isNotEqualTo(ConsistencyMarker.ORIGIN);

                    tx.appendEvent(newMessage);
                });

                execute(workUnit);

                await().untilAsserted(() -> assertThat(seenEvents)
                    .usingComparatorForType(EVENT_COMPARATOR, GenericEventMessage.class)
                    .containsExactly(event1, event2, event5, newMessage)
                );
            }
        }
    }

    @Nested
    protected class OverrideAppendCondition {

        /**
         * Uniqueness without sourcing — ensuring a course name is unique.
         * <p>
         * When creating a course, we want to guarantee no course with the same name already exists. We don't need to
         * source any events — we just need to check that no {@link CourseCreated} event with a matching
         * {@code courseName} tag has ever been appended (since {@link ConsistencyMarker#ORIGIN}).
         * <p>
         * The first creation succeeds. A second attempt with the same name is rejected because a matching event now
         * exists since ORIGIN.
         */
        @Test
        protected void shouldAppendWithOverriddenConditionWithoutSourcing() {
            // given - a unique course name
            String courseName = UUID.randomUUID().toString();
            Tag courseNameTag = new Tag("courseName", courseName);
            EventCriteria courseNameCriteria = EventCriteria.havingTags(courseNameTag);

            // when - first course creation: no sourcing, just enforce name uniqueness
            UnitOfWork uow1 = unitOfWork();

            uow1.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                // no source() — we don't need state, just uniqueness
                // condition is AppendCondition.none(); override with ORIGIN-based check
                tx.overrideAppendCondition(condition -> AppendCondition.withCriteria(courseNameCriteria));
                tx.appendEvent(message(new CourseCreated(courseName)));
            });

            execute(uow1);

            // then - event was persisted (sourcing finds 1 event with the courseName tag)
            assertThat(sourceCount(SourcingCondition.conditionFor(courseNameCriteria))).isEqualTo(1);

            // and - second attempt to create a course with the same name should fail
            // (CourseCreated with matching courseName tag now exists since ORIGIN)
            UnitOfWork uow2 = unitOfWork();

            uow2.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                tx.overrideAppendCondition(condition -> AppendCondition.withCriteria(courseNameCriteria));
                tx.appendEvent(message(new CourseCreated(courseName)));
            });

            assertThatThrownBy(() -> execute(uow2))
                    .isInstanceOf(AppendEventsTransactionRejectedException.class);
        }

        /**
         * Narrowing criteria — only conflict-relevant event types.
         * <p>
         * When handling a "subscribe student to course" command, we source both {@link StudentSubscribedToCourse} and
         * {@link StudentUnsubscribedFromCourse} events to build state (enrolled count, remaining places). However, only
         * {@code StudentSubscribedToCourse} events can cause a conflict (e.g., duplicate subscription, no remaining
         * places). {@code StudentUnsubscribedFromCourse} events only broaden state (free up places) and never
         * conflict.
         * <p>
         * By narrowing the append condition to only check for {@code StudentSubscribedToCourse}, a concurrent
         * unsubscription does not cause a false conflict.
         */
        @Test
        protected void narrowedCriteriaShouldAvoidFalseConflict() {
            // given - a course with subscriptions
            String courseId = UUID.randomUUID().toString();
            Tag courseIdTag = new Tag("courseId", courseId);
            EventCriteria subscribedAndUnsubscribedEvents = EventCriteria.havingTags(courseIdTag).andBeingOneOfTypes(
                    RESOLVER, StudentSubscribedToCourse.class, StudentUnsubscribedFromCourse.class
            );
            EventCriteria onlySubscribedEvents = EventCriteria.havingTags(courseIdTag).andBeingOneOfTypes(
                    RESOLVER, StudentSubscribedToCourse.class
            );

            // pre-populate: one student already subscribed
            UnitOfWork setup = unitOfWork();
            EventMessage initialSubscription = message(
                    new StudentSubscribedToCourse(courseId, "student-initial")
            );
            setup.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                tx.appendEvent(initialSubscription);
            });
            try {
                execute(setup);
            } catch (Exception e) {
                Assumptions.assumeFalse(
                        e instanceof TooManyTagsOnEventMessageException,
                        "The tested storage engine does not support DCB. Aborting test."
                );
            }

            CountDownLatch sourcingFinished = new CountDownLatch(2);
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);

            UnitOfWork uow1 = unitOfWork();
            UnitOfWork uow2 = unitOfWork();

            // TX1: subscribe a new student — source all subscription events for state,
            //      but narrow conflict detection to only StudentSubscribedToCourse
            EventMessage newSubscription = message(new StudentSubscribedToCourse(courseId, "student-new"));
            uow1.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing =
                        tx.source(SourcingCondition.conditionFor(subscribedAndUnsubscribedEvents));
                // consume the sourced events (1 initial subscription)
                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(1);

                // only StudentSubscribedToCourse can cause conflicts;
                // StudentUnsubscribedFromCourse just makes more places available
                tx.overrideAppendCondition(condition -> condition.replaceCriteria(onlySubscribedEvents));

                sourcingFinished.countDown();
                awaitLatch(latch1);

                tx.appendEvent(newSubscription);
            });

            // TX2: unsubscribe a student — this is a concurrent unsubscription
            EventMessage unsubscription = message(
                    new StudentUnsubscribedFromCourse(courseId, "student-initial")
            );
            uow2.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                MessageStream<? extends EventMessage> sourcing =
                        tx.source(SourcingCondition.conditionFor(subscribedAndUnsubscribedEvents));
                assertThat(sourcing.reduce(0, (c, m) -> ++c).join()).isEqualTo(1);

                sourcingFinished.countDown();
                awaitLatch(latch2);

                tx.appendEvent(unsubscription);
            });

            // when - TX2 commits first (appends StudentUnsubscribedFromCourse), then TX1 commits
            CompletableFuture<Void> execute1 = CompletableFuture.runAsync(() -> execute(uow1));
            CompletableFuture<Void> execute2 = CompletableFuture.runAsync(() -> execute(uow2));

            awaitLatch(sourcingFinished);

            // let TX2 (unsubscription) commit first
            latch2.countDown();
            assertDoesNotThrow(execute2::join);

            // TX1 should also succeed: its narrowed criteria (StudentSubscribedToCourse only)
            // does not match the StudentUnsubscribedFromCourse appended by TX2
            latch1.countDown();
            assertDoesNotThrow(execute1::join);
        }

        /**
         * Bypassing conflict detection — creating two courses with the same name.
         * <p>
         * Demonstrates that returning {@link AppendCondition#none()} from the override completely bypasses conflict
         * detection. Here, two courses are created with the same name — normally this would be rejected (as shown in
         * {@link #shouldAppendWithOverriddenConditionWithoutSourcing()}), but with the override returning
         * {@code none()}, both succeed.
         */
        @Test
        protected void overrideReturningNoneShouldBypassConflictDetection() {
            // given - a course name and first course already created with uniqueness check
            String courseName = UUID.randomUUID().toString();
            Tag courseNameTag = new Tag("courseName", courseName);
            EventCriteria courseNameCriteria = EventCriteria.havingTags(courseNameTag);

            UnitOfWork setup = unitOfWork();
            setup.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                tx.overrideAppendCondition(condition -> AppendCondition.withCriteria(courseNameCriteria));
                tx.appendEvent(message(new CourseCreated(courseName)));
            });
            execute(setup);

            // when - second course creation with same name, but override returns none()
            UnitOfWork uow = unitOfWork();
            uow.runOnInvocation(pc -> {
                EventStoreTransaction tx = eventStore.transaction(pc);
                // override returns none() — bypasses the uniqueness check entirely
                tx.overrideAppendCondition(condition -> AppendCondition.none());
                tx.appendEvent(message(new CourseCreated(courseName)));
            });

            // then - succeeds despite a CourseCreated with the same name already existing
            try {
                execute(uow);
            } catch (Exception e) {
                Assumptions.assumeFalse(
                        e instanceof AppendEventsTransactionRejectedException,
                        "The tested storage engine assume aggregate identifier and sequence uniqueness, "
                                + "but we're publishing for the same identifier twice. Aborting test."
                );
            }

            // verify both events are persisted
            assertThat(sourceCount(SourcingCondition.conditionFor(courseNameCriteria))).isEqualTo(2);
        }

        @Event
        record CourseCreated(@EventTag String courseName) {

        }

        @Event
        record StudentSubscribedToCourse(@EventTag String courseId, @EventTag String studentId) {

        }

        @Event
        record StudentUnsubscribedFromCourse(@EventTag String courseId, @EventTag String studentId) {

        }
    }


    protected void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch did not become available after 10 seconds");
            }
        }
        catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Creates a new message. Messages automatically get timestamps spaced 5 seconds apart.
     *
     * @param payload The event's payload.
     * @return An event message.
     */
    protected final EventMessage message(Object payload) {
        Instant timestamp = nextEventTimestamp;

        // accuracy limited as not all storage engines support nanosecond accuracy (which makes comparisons annoying)
        nextEventTimestamp = timestamp.plusSeconds(5).truncatedTo(ChronoUnit.MILLIS);

        MessageType messageType = RESOLVER.resolve(payload).orElseThrow();

        return new GenericEventMessage(
            "id-" + messageId++,
            messageType,
            payload,
            Map.of("meta", "data"),
            timestamp
        );
    }

    /**
     * Streams messages using the given condition until the specified number of messages
     * have become available, and returns a list with exactly {@code minMessages} messages.
     * <p>
     * If the expected number of messages were not streamed within a few seconds, an
     * assertion error is thrown.
     *
     * @param condition The condition, cannot be {@code null}.
     * @param minMessages The number of messages we expect.
     * @return The list containing the streamed messages.
     */
    protected final List<EventMessage> stream(StreamingCondition condition, int minMessages) {
        List<EventMessage> messages = new ArrayList<>();
        MessageStream<? extends EventMessage> stream = eventStore.open(condition, null);

        stream.setCallback(() -> Thread.ofVirtual().start(() -> {
            synchronized(messages) {
                int lastSize = -1;

                while(messages.size() != lastSize) {
                    lastSize = messages.size();
                    stream.next()
                        .map(Entry::message)
                        .map(m -> m.withConvertedPayload(CourseUpdated.class, CONVERTER))
                        .ifPresent(messages::add);
                }
            }
        }));

        await().untilAsserted(() -> {
            synchronized(messages) {
                assertThat(messages).hasSizeGreaterThanOrEqualTo(minMessages);
            }
        });

        stream.close();

        return messages.subList(0, minMessages);
    }

    /**
     * Fully sources all messages with the given sourcing condition and returns
     * them as a list.
     *
     * @param condition The condition, cannot be {@code null}.
     * @return The list containing the sourced messages.
     */
    protected final List<EventMessage> source(SourcingCondition condition) {
        return unitOfWork().executeWithResult(pc -> {
            EventStoreTransaction tx = eventStore.transaction(pc);
            MessageStream<? extends EventMessage> stream = tx.source(condition);

            return stream.reduce(
                new ArrayList<EventMessage>(),
                (list, entry) -> {
                    list.add(entry.message().withConvertedPayload(CourseUpdated.class, CONVERTER));

                    return list;
                }
            );
        }).join();
    }

    /**
     * Counts the number of events matching the given sourcing condition.
     *
     * @param condition The condition, cannot be {@code null}.
     * @return The number of matching events.
     */
    protected final int sourceCount(SourcingCondition condition) {
        return unitOfWork().executeWithResult(pc -> {
            EventStoreTransaction tx = eventStore.transaction(pc);
            MessageStream<? extends EventMessage> stream = tx.source(condition);

            return stream.reduce(0, (count, entry) -> count + 1);
        }).join();
    }

    /**
     * Executes a {@link UnitOfWork} and returns when it has completed.
     *
     * @param workUnit The {@link UnitOfWork} to execute.
     */
    protected final void execute(UnitOfWork workUnit) {
        try {
            workUnit.execute().get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException | TimeoutException e) {
            throw new AssertionError(e);
        }
        catch (ExecutionException e) {
            if(e.getCause() instanceof Error error) {
                throw error;
            }
            if(e.getCause() instanceof RuntimeException re) {
                throw re;
            }

            throw new RuntimeException(e);
        }
    }

    @Event
    record CourseUpdated(@EventTag(key = "Course") String id, String data) {}
}
