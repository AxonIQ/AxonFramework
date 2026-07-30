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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link StorageEngineBackedEventStore}.
 *
 * @author John Hendrikx
 */
@ExtendWith(MockitoExtension.class)
class StorageEngineBackedEventStoreTest {
    @Mock private EventStorageEngine eventStorageEngine;
    @Mock private EventBus eventBus;
    @Mock private TagResolver tagResolver;
    @Mock private AppendTransaction<String> appendTransaction;

    private StorageEngineBackedEventStore store;

    @BeforeEach
    void beforeEach() {
        this.store = new StorageEngineBackedEventStore(eventStorageEngine, eventBus, tagResolver);
    }

    @Test
    void futureFromPublishShouldNotCompleteBeforeCommitFutureCompletes() {
        CompletableFuture<String> commitFuture = new CompletableFuture<>();
        CompletableFuture<Void> eventBusPublishFuture = CompletableFuture.completedFuture(null);

        when(eventStorageEngine.appendEvents(any(), eq(null), anyList()))
            .thenReturn(CompletableFuture.completedFuture(appendTransaction));

        when(eventBus.publish(eq(null), anyList()))
            .thenReturn(eventBusPublishFuture);

        when(appendTransaction.commit()).thenReturn(commitFuture);

        CompletableFuture<Void> future = store.publish(
            null,
            Stream.of("A", "B", "C", "D").map(e -> new GenericEventMessage(new MessageType(e.getClass()), e)).toList()
        );

        // Check that publish future hasn't completed yet as AppendTransaction#commit hasn't yet completed:
        assertThat(future.isDone()).isFalse();

        // Now complete the commit:
        commitFuture.complete("ok");

        future.join();

        // Check that publish future has completed:
        assertThat(future.isDone()).isTrue();
    }

    @Test
    void futureFromPublishShouldNotCompleteBeforeAfterCommitFutureCompletes() {
        CompletableFuture<String> commitFuture = CompletableFuture.completedFuture("ok");
        CompletableFuture<ConsistencyMarker> afterCommitFuture = new CompletableFuture<>();
        CompletableFuture<Void> eventBusPublishFuture = CompletableFuture.completedFuture(null);

        when(eventStorageEngine.appendEvents(any(), eq(null), anyList()))
            .thenReturn(CompletableFuture.completedFuture(appendTransaction));

        when(eventBus.publish(eq(null), anyList()))
            .thenReturn(eventBusPublishFuture);

        when(appendTransaction.commit()).thenReturn(commitFuture);
        when(appendTransaction.afterCommit(any())).thenReturn(afterCommitFuture);

        CompletableFuture<Void> future = store.publish(
            null,
            Stream.of("A", "B", "C", "D").map(e -> new GenericEventMessage(new MessageType(e.getClass()), e)).toList()
        );

        // Check that publish future hasn't completed yet as AppendTransaction#afterCommit hasn't yet completed:
        assertThat(future.isDone()).isFalse();

        // Now complete afterCommit:
        afterCommitFuture.complete(ConsistencyMarker.ORIGIN);

        future.join();

        // Check that publish future has completed:
        assertThat(future.isDone()).isTrue();
    }
}
