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

package org.axonframework.eventsourcing.snapshot.store.tracing;

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.eventsourcing.eventstore.Position;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TracingSnapshotStoreTest {

    private TestSpanFactory spanFactory;
    private RecordingSnapshotStore delegate;
    private TracingSnapshotStore testSubject;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingSnapshotStore();
        testSubject = new TracingSnapshotStore(delegate, spanFactory);
    }

    @Nested
    class Store {

        @Test
        void opensAnInternalSpanWithQualifiedNameAndIdentifier() {
            // given
            QualifiedName name = new QualifiedName("Booking");
            delegate.storeResult = CompletableFuture.completedFuture(null);

            // when
            testSubject.store(name, "room-42", aSnapshot(), null).join();

            // then
            spanFactory.verifySpanCompleted("SnapshotStore.store Booking");
            spanFactory.verifySpanHasType("SnapshotStore.store Booking", TestSpanType.INTERNAL);
            spanFactory.verifySpanHasAttributeValue("SnapshotStore.store Booking",
                                                    "axoniq.entity.id", "room-42");
        }

        @Test
        void threadsProcessingContextToTheDelegate() {
            // given — the pc is what lets the span nest under the surrounding entity-source trace
            QualifiedName name = new QualifiedName("Booking");
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.store(name, "room-42", aSnapshot(), context).join();

            // then
            assertThat(delegate.storeContext).isSameAs(context);
        }
    }

    @Nested
    class Load {

        @Test
        void opensAnInternalSpanForLoad() {
            // given
            QualifiedName name = new QualifiedName("Booking");
            delegate.loadResult = CompletableFuture.completedFuture(null);

            // when
            testSubject.load(name, "room-42", null).join();

            // then
            spanFactory.verifySpanCompleted("SnapshotStore.load Booking");
            spanFactory.verifySpanHasType("SnapshotStore.load Booking", TestSpanType.INTERNAL);
        }

        @Test
        void threadsProcessingContextToTheDelegate() {
            // given
            QualifiedName name = new QualifiedName("Booking");
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.load(name, "room-42", context).join();

            // then
            assertThat(delegate.loadContext).isSameAs(context);
        }
    }

    private static final class RecordingSnapshotStore implements SnapshotStore {

        private CompletableFuture<Void> storeResult = CompletableFuture.completedFuture(null);
        private CompletableFuture<@Nullable Snapshot> loadResult = CompletableFuture.completedFuture(null);
        private @Nullable ProcessingContext storeContext;
        private @Nullable ProcessingContext loadContext;

        @Override
        public CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier, Snapshot snapshot,
                                             @Nullable ProcessingContext context) {
            this.storeContext = context;
            return storeResult;
        }

        @Override
        public CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                                          @Nullable ProcessingContext context) {
            this.loadContext = context;
            return loadResult;
        }
    }

    private static Snapshot aSnapshot() {
        return new Snapshot(Position.START, "1", "payload", Instant.now(), Map.of());
    }
}
