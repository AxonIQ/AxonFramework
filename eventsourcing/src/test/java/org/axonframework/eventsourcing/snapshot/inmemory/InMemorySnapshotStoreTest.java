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

package org.axonframework.eventsourcing.snapshot.inmemory;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.GlobalIndexPositions;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.api.SnapshotCandidate;
import org.axonframework.eventsourcing.snapshot.api.SnapshotMatch;
import org.axonframework.messaging.core.QualifiedName;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link InMemorySnapshotStore}.
 */
class InMemorySnapshotStoreTest {

    private static final QualifiedName SNAPSHOT_NAME = new QualifiedName("Ledger");
    private static final String IDENTIFIER = "ledger-1";
    private static final List<SnapshotCandidate> CANDIDATES =
        List.of(new SnapshotCandidate(SNAPSHOT_NAME, Set.of("1.0")));

    @Nested
    class StoreAndLoadWithConverter {

        private final InMemorySnapshotStore testSubject = new InMemorySnapshotStore(new CopyingConverter());

        @Test
        void storedSnapshotIsIsolatedFromLaterMutationsOfTheOriginalPayload() throws Exception {
            // given
            MutableState payload = new MutableState(3);
            Snapshot snapshot = snapshot(payload);

            // when
            testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot, null).get(1, TimeUnit.SECONDS);
            payload.setTotal(5);
            Snapshot loaded = loadSnapshot(testSubject);

            // then
            assertThat(loaded).isNotNull();
            assertThat(loaded.payload()).isInstanceOf(MutableState.class);
            assertThat(((MutableState) loaded.payload()).getTotal()).isEqualTo(3);
            assertThat(loaded.payload()).isNotSameAs(payload);
        }

        @Test
        void loadedSnapshotIsIsolatedFromLaterMutationsOfTheReturnedPayload() throws Exception {
            // given
            testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot(new MutableState(3)), null)
                       .get(1, TimeUnit.SECONDS);
            Snapshot firstLoad = loadSnapshot(testSubject);

            // when
            ((MutableState) firstLoad.payload()).setTotal(99);
            Snapshot secondLoad = loadSnapshot(testSubject);

            // then
            assertThat(secondLoad).isNotNull();
            assertThat(((MutableState) secondLoad.payload()).getTotal()).isEqualTo(3);
            assertThat(secondLoad.payload()).isNotSameAs(firstLoad.payload());
        }

        @Test
        void storeReplacesThePreviousSnapshotForTheSameIdentifier() throws Exception {
            // given
            testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot(new MutableState(3)), null)
                       .get(1, TimeUnit.SECONDS);

            // when
            testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot(new MutableState(7)), null)
                       .get(1, TimeUnit.SECONDS);
            Snapshot loaded = loadSnapshot(testSubject);

            // then
            assertThat(loaded).isNotNull();
            assertThat(((MutableState) loaded.payload()).getTotal()).isEqualTo(7);
        }

        @Test
        void loadReturnsNullWhenNoSnapshotWasStored() throws Exception {
            // given / when
            Snapshot loaded = loadSnapshot(testSubject);

            // then
            assertThat(loaded).isNull();
        }

        @Test
        void copiesByteArrayPayloads() throws Exception {
            // given
            byte[] payload = {1, 2, 3};
            testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot(payload), null).get(1, TimeUnit.SECONDS);

            // when
            payload[0] = 9;
            Snapshot loaded = loadSnapshot(testSubject);

            // then
            assertThat(loaded).isNotNull();
            assertThat((byte[]) loaded.payload()).containsExactly(1, 2, 3);
            assertThat(loaded.payload()).isNotSameAs(payload);
        }
    }

    @Nested
    class StoreAndLoadWithJacksonConverter {

        private final InMemorySnapshotStore testSubject = new InMemorySnapshotStore(new JacksonConverter());

        @Test
        void copiesMutablePayloadsThroughJackson() throws Exception {
            // given
            MutableState payload = new MutableState(3);

            // when
            testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot(payload), null).get(1, TimeUnit.SECONDS);
            payload.setTotal(5);
            Snapshot loaded = loadSnapshot(testSubject);

            // then
            assertThat(loaded).isNotNull();
            assertThat(((MutableState) loaded.payload()).getTotal()).isEqualTo(3);
        }
    }

    @Nested
    class LoadWithCandidates {

        private static final QualifiedName FORMER_NAME = new QualifiedName("FormerLedger");

        private final InMemorySnapshotStore testSubject = new InMemorySnapshotStore(new CopyingConverter());

        @Test
        void shouldReturnTheSnapshotOfTheFirstCandidateWithAnAcceptableOne() throws Exception {
            // given
            store(SNAPSHOT_NAME, snapshot(new MutableState(1), "1.0"));
            store(FORMER_NAME, snapshot(new MutableState(2), "1.0"));

            // when
            SnapshotMatch match = load(candidate(SNAPSHOT_NAME, "1.0"), candidate(FORMER_NAME, "1.0"));

            // then
            assertThat(match).isNotNull();
            assertThat(match.qualifiedName()).isEqualTo(SNAPSHOT_NAME);
            assertThat(((MutableState) match.snapshot().payload()).getTotal()).isEqualTo(1);
        }

        @Test
        void shouldFallBackToALaterCandidateWhenAnEarlierOneHasNoSnapshot() throws Exception {
            // given
            store(FORMER_NAME, snapshot(new MutableState(2), "1.0"));

            // when
            SnapshotMatch match = load(candidate(SNAPSHOT_NAME, "1.0"), candidate(FORMER_NAME, "1.0"));

            // then
            assertThat(match).isNotNull();
            assertThat(match.qualifiedName()).isEqualTo(FORMER_NAME);
        }

        @Test
        void shouldPassOverASnapshotOfAVersionThatIsNotAcceptable() throws Exception {
            // given a snapshot written by a newer version of the application than the one now loading it
            store(SNAPSHOT_NAME, snapshot(new MutableState(1), "2.0"));

            // when
            SnapshotMatch match = load(candidate(SNAPSHOT_NAME, "1.0"));

            // then
            assertThat(match).isNull();
        }

        @Test
        void shouldMoveOnToTheNextCandidateWhenASnapshotIsOfAnUnacceptableVersion() throws Exception {
            // given
            store(SNAPSHOT_NAME, snapshot(new MutableState(1), "2.0"));
            store(FORMER_NAME, snapshot(new MutableState(2), "1.0"));

            // when
            SnapshotMatch match = load(candidate(SNAPSHOT_NAME, "1.0"), candidate(FORMER_NAME, "1.0"));

            // then
            assertThat(match).isNotNull();
            assertThat(match.qualifiedName()).isEqualTo(FORMER_NAME);
        }

        @Test
        void shouldAcceptAnyVersionInTheAcceptableSet() throws Exception {
            // given
            store(SNAPSHOT_NAME, snapshot(new MutableState(1), "1.0"));

            // when
            SnapshotMatch match = load(new SnapshotCandidate(SNAPSHOT_NAME, Set.of("1.0", "2.0")));

            // then
            assertThat(match).isNotNull();
            assertThat(match.snapshot().version()).isEqualTo("1.0");
        }

        @Test
        void shouldIgnoreAnAggregateBasedCandidate() throws Exception {
            // given a snapshot under the aggregate-based candidate's name, which an aggregate-based lookup cannot find
            store(FORMER_NAME, snapshot(new MutableState(2), "1.0"));

            // when
            SnapshotMatch match = testSubject.load(
                List.of(candidate(SNAPSHOT_NAME, "1.0")), candidate(FORMER_NAME, "1.0"), IDENTIFIER, null
            ).get(1, TimeUnit.SECONDS);

            // then
            assertThat(match).isNull();
        }

        private void store(QualifiedName name, Snapshot snapshot) throws Exception {
            testSubject.store(name, IDENTIFIER, snapshot, null).get(1, TimeUnit.SECONDS);
        }

        private @Nullable SnapshotMatch load(SnapshotCandidate... candidates) throws Exception {
            return testSubject.load(List.of(candidates), IDENTIFIER, null).get(1, TimeUnit.SECONDS);
        }

        private static SnapshotCandidate candidate(QualifiedName name, String version) {
            return new SnapshotCandidate(name, Set.of(version));
        }
    }

    @Nested
    class ConversionFailures {

        @Test
        void storeFailsWhenTheConverterReturnsNullSerialization() {
            // given
            InMemorySnapshotStore testSubject = new InMemorySnapshotStore(new NullSerializationConverter());

            // when / then
            assertThatThrownBy(() -> testSubject.store(
                SNAPSHOT_NAME, IDENTIFIER, snapshot(new MutableState(3)), null
            )).isInstanceOf(ConversionException.class)
              .hasMessageContaining("null serialization");
        }

        @Test
        void storeFailsWhenTheConverterReturnsTheOriginalPayload() {
            // given
            MutableState payload = new MutableState(3);
            InMemorySnapshotStore testSubject = new InMemorySnapshotStore(new IdentityOnDeserializeConverter(payload));

            // when / then
            assertThatThrownBy(() -> testSubject.store(SNAPSHOT_NAME, IDENTIFIER, snapshot(payload), null))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("independent copy");
        }
    }

    @Nested
    class ArgumentValidation {

        private final InMemorySnapshotStore testSubject = new InMemorySnapshotStore(new CopyingConverter());

        @Test
        void storeRejectsNullQualifiedName() {
            // given / when / then
            assertThatThrownBy(() -> testSubject.store(null, IDENTIFIER, snapshot(new MutableState(1)), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("qualifiedName");
        }

        @Test
        void storeRejectsNullIdentifier() {
            // given / when / then
            assertThatThrownBy(() -> testSubject.store(SNAPSHOT_NAME, null, snapshot(new MutableState(1)), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("identifier");
        }

        @Test
        void storeRejectsNullSnapshot() {
            // given / when / then
            assertThatThrownBy(() -> testSubject.store(SNAPSHOT_NAME, IDENTIFIER, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("snapshot");
        }

        @Test
        void loadRejectsNullCandidates() {
            // given / when / then
            assertThatThrownBy(() -> testSubject.load((List<SnapshotCandidate>) null, IDENTIFIER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("candidates");
        }

        @Test
        void shouldRejectEmptyCandidatesOnLoad() {
            // given / when / then
            assertThatThrownBy(() -> testSubject.load(List.of(), IDENTIFIER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidates");
        }

        @Test
        void loadRejectsNullIdentifier() {
            // given / when / then
            assertThatThrownBy(() -> testSubject.load(CANDIDATES, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("identifier");
        }

        @Test
        void constructorRejectsNullConverter() {
            // given / when / then
            assertThatThrownBy(() -> new InMemorySnapshotStore(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("converter");
        }
    }

    private static Snapshot snapshot(Object payload) {
        return snapshot(payload, "1.0");
    }

    private static Snapshot snapshot(Object payload, String version) {
        return new Snapshot(
            GlobalIndexPositions.of(3),
            version,
            payload,
            Instant.parse("2026-01-01T00:00:00Z"),
            Map.of()
        );
    }

    private static @Nullable Snapshot loadSnapshot(InMemorySnapshotStore store) throws Exception {
        SnapshotMatch match = store.load(CANDIDATES, IDENTIFIER, null).get(1, TimeUnit.SECONDS);

        return match == null ? null : match.snapshot();
    }

    /**
     * Mutable snapshot payload used to verify that the store does not retain the caller's instance.
     */
    public static class MutableState {

        private int total;

        public MutableState() {
        }

        public MutableState(int total) {
            this.total = total;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }
    }

    /**
     * Test {@link Converter} that copies {@link MutableState} through a byte-array round-trip.
     */
    private static final class CopyingConverter implements Converter {

        @Override
        @SuppressWarnings("unchecked")
        public <T> T convert(Object input, Type targetType) {
            if (input instanceof MutableState state && byte[].class.equals(targetType)) {
                return (T) Integer.toString(state.getTotal()).getBytes(StandardCharsets.UTF_8);
            }
            if (input instanceof byte[] bytes && MutableState.class.equals(targetType)) {
                return (T) new MutableState(Integer.parseInt(new String(bytes, StandardCharsets.UTF_8)));
            }
            if (input != null && input.getClass().equals(targetType)) {
                return (T) input;
            }
            throw new ConversionException("Cannot convert " + typeName(input) + " to " + targetType);
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // nothing to describe
        }

        private static String typeName(Object input) {
            return input == null ? "null" : input.getClass().getName();
        }
    }

    /**
     * Test {@link Converter} that serializes {@link MutableState} but deserializes back to the original instance.
     */
    private static final class IdentityOnDeserializeConverter implements Converter {

        private final MutableState original;

        private IdentityOnDeserializeConverter(MutableState original) {
            this.original = original;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T convert(Object input, Type targetType) {
            if (input instanceof MutableState state && byte[].class.equals(targetType)) {
                return (T) Integer.toString(state.getTotal()).getBytes(StandardCharsets.UTF_8);
            }
            if (input instanceof byte[] && MutableState.class.equals(targetType)) {
                return (T) original;
            }
            throw new ConversionException("Cannot convert " + input + " to " + targetType);
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // nothing to describe
        }
    }

    /**
     * Test {@link Converter} that cannot serialize snapshot payloads.
     */
    private static final class NullSerializationConverter implements Converter {

        @Override
        public <T> T convert(Object input, Type targetType) {
            return null;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // nothing to describe
        }
    }
}
