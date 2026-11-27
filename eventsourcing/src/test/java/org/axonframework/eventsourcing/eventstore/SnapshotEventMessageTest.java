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

import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.VersionedType;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link SnapshotEventMessage}.
 *
 * @author John Hendrikx
 */
class SnapshotEventMessageTest {

    private static final QualifiedName NAME = new QualifiedName("account");
    private static final Snapshot SNAPSHOT =
        new Snapshot(new GlobalIndexPosition(42), "1.0.0", "state", Instant.EPOCH, Map.of());

    @Test
    void shouldHaveTheSameTypeForEverySnapshot() {
        // given
        SnapshotEventMessage message = new SnapshotEventMessage(NAME, SNAPSHOT);
        SnapshotEventMessage other = new SnapshotEventMessage(new QualifiedName("customer"), SNAPSHOT);

        // when / then
        assertThat(message.type()).isEqualTo(new MessageType(SnapshotEventMessage.class));
        assertThat(other.type()).isEqualTo(message.type());
    }

    @Test
    void shouldCarryTheNameItWasFoundUnderAndItsVersionAsSnapshotType() {
        // when
        SnapshotEventMessage message = new SnapshotEventMessage(NAME, SNAPSHOT);

        // then
        assertThat(message.snapshotType()).isEqualTo(VersionedType.of(NAME, "1.0.0"));
        assertThat(message.payload()).isSameAs(SNAPSHOT);
    }

    @Test
    void shouldRejectANullQualifiedName() {
        assertThatThrownBy(() -> new SnapshotEventMessage(null, SNAPSHOT))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullSnapshot() {
        assertThatThrownBy(() -> new SnapshotEventMessage(NAME, null))
            .isInstanceOf(NullPointerException.class);
    }

    // temporary: covers the deprecated bridge Axoniq Framework still uses
    @Test
    @SuppressWarnings("removal")
    void shouldHaveNoSnapshotTypeWhenConstructedWithoutAName() {
        // when
        SnapshotEventMessage message = new SnapshotEventMessage(SNAPSHOT);

        // then
        assertThat(message.type()).isEqualTo(new MessageType(SnapshotEventMessage.class));
        assertThat(message.snapshotType()).isNull();
    }
}
