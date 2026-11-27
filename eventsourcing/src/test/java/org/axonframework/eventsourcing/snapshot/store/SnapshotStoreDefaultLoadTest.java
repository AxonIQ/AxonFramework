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

package org.axonframework.eventsourcing.snapshot.store;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.Position;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.api.SnapshotCandidate;
import org.axonframework.eventsourcing.snapshot.api.SnapshotMatch;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * Temporary: covers the default candidate load that SnapshotStore provides on top of the deprecated single-name load,
 * so stores in Axoniq Framework that only implement the latter keep working. Remove together with both.
 */
class SnapshotStoreDefaultLoadTest {

    private static final QualifiedName CURRENT_NAME = new QualifiedName("Order");
    private static final QualifiedName FORMER_NAME = new QualifiedName("LegacyOrder");
    private static final String IDENTIFIER = "order-1";

    private final SingleNameSnapshotStore testSubject = new SingleNameSnapshotStore();

    @Test
    void shouldReturnTheSnapshotOfTheFirstCandidateWithAnAcceptableOne() throws Exception {
        // given
        testSubject.put(CURRENT_NAME, snapshot("1.0"));
        testSubject.put(FORMER_NAME, snapshot("1.0"));

        // when
        SnapshotMatch match = load(candidate(CURRENT_NAME, "1.0"), candidate(FORMER_NAME, "1.0"));

        // then
        assertThat(match).isNotNull();
        assertThat(match.qualifiedName()).isEqualTo(CURRENT_NAME);
    }

    @Test
    void shouldMoveOnToTheNextCandidateWhenASnapshotIsOfAnUnacceptableVersion() throws Exception {
        // given
        testSubject.put(CURRENT_NAME, snapshot("2.0"));
        testSubject.put(FORMER_NAME, snapshot("1.0"));

        // when
        SnapshotMatch match = load(candidate(CURRENT_NAME, "1.0"), candidate(FORMER_NAME, "1.0"));

        // then
        assertThat(match).isNotNull();
        assertThat(match.qualifiedName()).isEqualTo(FORMER_NAME);
    }

    @Test
    void shouldReturnNullWhenNoCandidateYieldsAnAcceptableSnapshot() throws Exception {
        // given
        testSubject.put(CURRENT_NAME, snapshot("2.0"));

        // when
        SnapshotMatch match = load(candidate(CURRENT_NAME, "1.0"), candidate(FORMER_NAME, "1.0"));

        // then
        assertThat(match).isNull();
    }

    @Test
    void shouldAcceptAnyVersionForACandidateWithNoVersions() throws Exception {
        // given
        testSubject.put(CURRENT_NAME, snapshot("2.0"));

        // when
        SnapshotMatch match = load(new SnapshotCandidate(CURRENT_NAME, Set.of()));

        // then
        assertThat(match).isNotNull();
        assertThat(match.snapshot().version()).isEqualTo("2.0");
    }

    @Test
    void shouldRejectEmptyCandidates() {
        // given / when / then
        assertThatThrownBy(() -> testSubject.load(List.of(), IDENTIFIER, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("candidates");
    }

    private @Nullable SnapshotMatch load(SnapshotCandidate... candidates) throws Exception {
        return testSubject.load(List.of(candidates), IDENTIFIER, null).get(1, TimeUnit.SECONDS);
    }

    private static SnapshotCandidate candidate(QualifiedName name, String version) {
        return new SnapshotCandidate(name, Set.of(version));
    }

    private static Snapshot snapshot(String version) {
        return new Snapshot(Position.START, version, "payload", Instant.now(), Map.of());
    }

    /*
     * Implements only the single-name load, as the stores in Axoniq Framework do, relying on the default for the
     * candidate load.
     */
    private static final class SingleNameSnapshotStore implements SnapshotStore {

        private final Map<QualifiedName, Snapshot> snapshots = new HashMap<>();

        void put(QualifiedName name, Snapshot snapshot) {
            snapshots.put(name, snapshot);
        }

        @Override
        public CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier, Snapshot snapshot,
                                             @Nullable ProcessingContext context) {
            snapshots.put(qualifiedName, snapshot);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @Deprecated
        public CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                                          @Nullable ProcessingContext context) {
            return CompletableFuture.completedFuture(snapshots.get(qualifiedName));
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // not needed by these tests
        }
    }
}
