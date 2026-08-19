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

package org.axonframework.eventsourcing.eventstore.inmemory;

import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineTestSuite;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.TerminalEventMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.*;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * Test class validating the {@link InMemoryEventStorageEngine}.
 *
 * @author Stefan Dragisic
 * @author Steven van Beelen
 */
class InMemoryEventStorageEngineTest extends StorageEngineTestSuite<InMemoryEventStorageEngine> {

    private static final Set<Tag> TAGS = Set.of(new Tag("TEST", "batch"));
    private static final EventCriteria CRITERIA = EventCriteria.havingTags(new Tag("TEST", "batch"));
    private static final int BATCH_SIZE = 4;
    private static final StreamingCondition WHOLE_STORE =
            StreamingCondition.startingFrom(new GlobalSequenceTrackingToken(-1));

    @Override
    protected InMemoryEventStorageEngine createStorageEngine() {
        return new InMemoryEventStorageEngine();
    }

    @Override
    protected ProcessingContext processingContext() {
        return null;
    }

    @Override  // disable this unsupported scenario
    protected void twoIndependentStorageEnginesShouldSeeEachOthersAppends() {
    }

    @Nested
    class AReadTakenWhileABatchIsBeingStored {

        @Test
        void streamsNoneOfThatBatch() {
            // given a store holding nothing
            InMemoryEventStorageEngine testSubject = new InMemoryEventStorageEngine();
            List<String> observedMidCommit = new ArrayList<>();

            // when the whole store is streamed at the moment the batch's second event is about to be stored
            InterruptedBatch batch = new InterruptedBatch(
                    ignored -> observedMidCommit.addAll(drain(testSubject.stream(WHOLE_STORE)))
            );
            commit(testSubject, batch);

            // then the read happened inside the window, or this test proves nothing
            assertThat(batch.wasInterrupted()).as("the store was read while the batch was being stored").isTrue();

            // then the reader saw none of the batch, rather than the events stored before the interruption
            assertThat(observedMidCommit).as("events a mid-commit read saw of an unfinished batch").isEmpty();

            // and once the commit returned, the batch is visible in full
            assertThat(drain(testSubject.stream(WHOLE_STORE))).hasSize(BATCH_SIZE);
        }

        @Test
        void sourcesNoneOfThatBatch() {
            // given a store holding nothing
            InMemoryEventStorageEngine testSubject = new InMemoryEventStorageEngine();
            List<String> observedMidCommit = new ArrayList<>();

            // when the batch's own criteria are sourced at the moment its second event is about to be stored
            SourcingCondition condition = SourcingCondition.conditionFor(CRITERIA);
            InterruptedBatch batch = new InterruptedBatch(
                    ignored -> observedMidCommit.addAll(drain(testSubject.source(condition, null)))
            );
            commit(testSubject, batch);

            // then the read happened inside the window, or this test proves nothing
            assertThat(batch.wasInterrupted()).as("the store was read while the batch was being stored").isTrue();

            // then the reader saw none of the batch, rather than the events stored before the interruption
            assertThat(observedMidCommit).as("events a mid-commit read saw of an unfinished batch").isEmpty();

            // and once the commit returned, the batch is visible in full
            assertThat(drain(testSubject.source(condition, null))).hasSize(BATCH_SIZE);
        }

        @Test
        void reportsALatestTokenThatIsNotInsideThatBatch() {
            // given a store holding nothing
            InMemoryEventStorageEngine testSubject = new InMemoryEventStorageEngine();
            List<TrackingToken> tokensMidCommit = new ArrayList<>();

            // when the head token is asked for at the moment the batch's second event is about to be stored
            InterruptedBatch batch = new InterruptedBatch(
                    ignored -> tokensMidCommit.add(joinAndUnwrap(testSubject.latestToken()))
            );
            commit(testSubject, batch);

            // then the token points before the batch rather than into it, so a processor starting at the head does not
            // begin halfway through a transaction
            assertThat(batch.wasInterrupted()).as("the head token was read while the batch was being stored").isTrue();
            assertThat(tokensMidCommit).containsExactly(new GlobalSequenceTrackingToken(-1));

            // and after the commit the head token covers the whole batch
            assertThat(joinAndUnwrap(testSubject.latestToken())).isEqualTo(new GlobalSequenceTrackingToken(BATCH_SIZE));
        }
    }

    private static void commit(InMemoryEventStorageEngine engine, List<TaggedEventMessage<?>> events) {
        EventStorageEngine.AppendTransaction<?> transaction =
                joinAndUnwrap(engine.appendEvents(AppendCondition.none(), null, events));
        joinAndUnwrap(transaction.commit());
    }

    private static List<String> drain(MessageStream<EventMessage> stream) {
        List<String> payloads = new ArrayList<>();
        for (Optional<MessageStream.Entry<EventMessage>> entry = stream.next();
             entry.isPresent();
             entry = stream.next()) {
            EventMessage event = entry.get().message();
            if (event != TerminalEventMessage.INSTANCE) {
                payloads.add(event.identifier());
            }
        }
        return payloads;
    }

    /**
     * A batch of events that runs the given action once, between the storage of its first and its second event.
     * <p>
     * The engine walks the list it is handed one element at a time, so the action fires after the first event is in
     * storage and before the remaining ones are. That is the only window in which a batch is stored in part, and
     * reaching it without threads keeps the test deterministic.
     */
    private static final class InterruptedBatch extends AbstractList<TaggedEventMessage<?>> {

        private final List<TaggedEventMessage<?>> events;
        private final Consumer<Integer> onSecondEvent;
        private boolean interrupted;

        private InterruptedBatch(Consumer<Integer> onSecondEvent) {
            this.events = new ArrayList<>();
            EventTestUtils.createEvents(BATCH_SIZE)
                          .forEach(event -> this.events.add(new GenericTaggedEventMessage<>(event, TAGS)));
            this.onSecondEvent = onSecondEvent;
        }

        @Override
        public TaggedEventMessage<?> get(int index) {
            if (index == 1 && !interrupted) {
                interrupted = true;
                onSecondEvent.accept(index);
            }
            return events.get(index);
        }

        @Override
        public int size() {
            return events.size();
        }

        private boolean wasInterrupted() {
            return interrupted;
        }
    }
}
