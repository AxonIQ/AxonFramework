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

import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictDiagnosingEventStorageEngineTest {

    private static final Tag CONFLICTING_TAG = Tag.of("id", "42");

    private final InMemoryEventStorageEngine delegate = new InMemoryEventStorageEngine();
    private final ConflictDiagnosingEventStorageEngine testSubject = new ConflictDiagnosingEventStorageEngine(delegate);

    @Test
    void identifiesAConflictingEventWhenTheDelegateRejectsEagerlyDuringAppend() {
        // given a pre-existing event carrying the tag the new append's criteria will be checked against
        EventMessage conflictingEvent = appendUnconditionally(EventTestUtils.createEvent(0), CONFLICTING_TAG);

        // when appending with a condition that conflicts with it right away
        AppendCondition condition = AppendCondition.withCriteria(EventCriteria.havingTags(CONFLICTING_TAG));

        // then
        assertThatThrownBy(() -> joinAndUnwrap(
                testSubject.appendEvents(condition, null,
                                         List.of(new GenericTaggedEventMessage<>(EventTestUtils.createEvent(1), Set.of())))
        )).isInstanceOf(AppendEventsTransactionRejectedException.class)
          .satisfies(exception -> {
              AppendEventsTransactionRejectedException rejection = (AppendEventsTransactionRejectedException) exception;
              assertThat(rejection.tags()).contains(CONFLICTING_TAG);
              assertThat(rejection.conflictingEvent())
                      .map(EventMessage::identifier)
                      .contains(conflictingEvent.identifier());
          });
    }

    @Test
    void identifiesAConflictingEventWhenTheDelegateRejectsLaterDuringCommit() {
        // given an append that does not conflict yet when started
        AppendCondition condition = AppendCondition.withCriteria(EventCriteria.havingTags(CONFLICTING_TAG));
        AppendTransaction<?> transaction = joinAndUnwrap(
                testSubject.appendEvents(condition, null,
                                         List.of(new GenericTaggedEventMessage<>(EventTestUtils.createEvent(1), Set.of())))
        );

        // and a conflicting event appended and committed by someone else afterward
        EventMessage conflictingEvent = appendUnconditionally(EventTestUtils.createEvent(0), CONFLICTING_TAG);

        // when committing the original, now-conflicting, transaction
        assertThatThrownBy(() -> joinAndUnwrap(transaction.commit()))
                .isInstanceOf(AppendEventsTransactionRejectedException.class)
                .satisfies(exception -> {
                    AppendEventsTransactionRejectedException rejection = (AppendEventsTransactionRejectedException) exception;
                    assertThat(rejection.tags()).contains(CONFLICTING_TAG);
                    assertThat(rejection.conflictingEvent())
                            .map(EventMessage::identifier)
                            .contains(conflictingEvent.identifier());
                });
    }

    private EventMessage appendUnconditionally(EventMessage event, Tag tag) {
        AppendTransaction<?> transaction = joinAndUnwrap(
                delegate.appendEvents(AppendCondition.none(), null,
                                      List.of(new GenericTaggedEventMessage<>(event, Set.of(tag))))
        );
        Object commitResult = joinAndUnwrap(uncheckedCommit(transaction));
        joinAndUnwrap(uncheckedAfterCommit(transaction, commitResult));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> uncheckedCommit(AppendTransaction<?> transaction) {
        return ((AppendTransaction<Object>) transaction).commit();
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<ConsistencyMarker> uncheckedAfterCommit(AppendTransaction<?> transaction,
                                                                              Object commitResult) {
        return ((AppendTransaction<Object>) transaction).afterCommit(commitResult);
    }
}
