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

package org.axonframework.eventsourcing.snapshotting;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventhandling.DomainEventData;
import org.axonframework.eventhandling.DomainEventMessage;
import org.axonframework.eventhandling.GenericDomainEventMessage;
import org.axonframework.eventsourcing.eventstore.jpa.SnapshotEventEntry;
import org.axonframework.eventsourcing.utils.TestSerializer;
import org.axonframework.modelling.command.AggregateRoot;
import org.axonframework.serialization.Revision;
import org.axonframework.serialization.Serializer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link RevisionSnapshotFilter}.
 * <p>
 * Snapshots are stored under an aggregate's <em>declared type</em> (its {@link DomainEventData#getType()}), which
 * defaults to the simple class name and can be overridden through {@link AggregateRoot#type()}. The fixtures below
 * therefore store snapshots under that declared type, mirroring how snapshots are physically stored.
 *
 * @author Steven van Beelen
 */
class RevisionSnapshotFilterTest {

    private static final String EXPECTED_REVISION = "LET ME IN";
    private static final String OTHER_REVISION = "some-other-revision";

    private final Serializer serializer = TestSerializer.xStreamSerializer();

    @Test
    void allowsSnapshotMatchingDeclaredTypeAndRevision() {
        RevisionSnapshotFilter testSubject = filterFor(AggregateWithExpectedRevision.class, EXPECTED_REVISION);

        assertTrue(testSubject.allow(snapshotOf(AggregateWithExpectedRevision.class,
                                                new AggregateWithExpectedRevision())));
    }

    /**
     * A filter only votes on its own aggregate type; snapshots of other aggregates must be allowed through, as filters
     * are combined in an AND operation across all aggregates.
     */
    @Test
    void allowsSnapshotOfAnotherAggregateType() {
        RevisionSnapshotFilter testSubject = filterFor(AggregateWithExpectedRevision.class, EXPECTED_REVISION);

        assertTrue(testSubject.allow(snapshotOf(AggregateWithOtherRevision.class,
                                                new AggregateWithOtherRevision())));
    }

    @Test
    void disallowsSnapshotMatchingDeclaredTypeButWithWrongRevision() {
        RevisionSnapshotFilter testSubject = filterFor(AggregateWithOtherRevision.class, EXPECTED_REVISION);

        assertFalse(testSubject.allow(snapshotOf(AggregateWithOtherRevision.class,
                                                 new AggregateWithOtherRevision())));
    }

    /**
     * {@code type(Class)} must resolve to the declared type - the simple name here - to match the stored snapshot.
     */
    @Test
    void typeByClassResolvesToSimpleNameWhenNoAggregateRootTypeOverride() {
        RevisionSnapshotFilter testSubject =
                RevisionSnapshotFilter.builder()
                                      .type(AggregateWithExpectedRevision.class)
                                      .revision(EXPECTED_REVISION)
                                      .build();

        assertTrue(testSubject.allow(snapshotOf(AggregateWithExpectedRevision.class,
                                                new AggregateWithExpectedRevision())));
    }

    /**
     * The snapshot is stored under the {@link AggregateRoot#type()} value, which is what {@code type(Class)} must
     * resolve to.
     */
    @Test
    void typeByClassHonorsAggregateRootTypeOverride() {
        RevisionSnapshotFilter testSubject =
                RevisionSnapshotFilter.builder()
                                      .type(CustomTypedAggregate.class)
                                      .revision(EXPECTED_REVISION)
                                      .build();

        assertTrue(testSubject.allow(snapshotOf("custom-type", new CustomTypedAggregate())));
    }

    /**
     * Configuring the filter with a fully-qualified class name while snapshots are stored under the declared (simple)
     * type is a misconfiguration: the types never match, so the filter abstains (returns {@code true}) without
     * evaluating the revision, and an outdated snapshot is kept.
     */
    @Test
    void fullyQualifiedTypeDoesNotMatchDeclaredTypeAndAllowsSnapshot() {
        String fullyQualifiedType = AggregateWithOtherRevision.class.getName();
        assertNotEquals(fullyQualifiedType, AggregateWithOtherRevision.class.getSimpleName());

        RevisionSnapshotFilter testSubject =
                RevisionSnapshotFilter.builder()
                                      .type(fullyQualifiedType)
                                      .revision(EXPECTED_REVISION)
                                      .build();

        DomainEventData<byte[]> outdatedSnapshot =
                snapshotOf(AggregateWithOtherRevision.class, new AggregateWithOtherRevision());

        assertTrue(testSubject.allow(outdatedSnapshot),
                   "A fully-qualified filter type never matches the stored simple-name type, "
                           + "so the filter keeps the outdated snapshot");
    }

    @Test
    void buildWithNullOrEmptyTypeThrowsAxonConfigurationException() {
        RevisionSnapshotFilter.Builder builderTestSubject = RevisionSnapshotFilter.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.type(""));
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.type((String) null));
    }

    @Test
    void buildWithNullOrEmptyRevisionThrowsAxonConfigurationException() {
        RevisionSnapshotFilter.Builder builderTestSubject = RevisionSnapshotFilter.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.revision(""));
    }

    @Test
    void buildWithoutTypeThrowsAxonConfigurationException() {
        RevisionSnapshotFilter.Builder builderTestSubject = RevisionSnapshotFilter.builder()
                                                                                  .revision(EXPECTED_REVISION);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void buildWithBlankRevisionThrowsAxonConfigurationException() {
        assertThrows(AxonConfigurationException.class, () -> RevisionSnapshotFilter.builder()
                                                                                   .type(AggregateWithExpectedRevision.class)
                                                                                   .revision("")
                                                                                   .build());
    }

    private RevisionSnapshotFilter filterFor(Class<?> aggregateType, String revision) {
        return RevisionSnapshotFilter.builder()
                                     .type(aggregateType.getSimpleName())
                                     .revision(revision)
                                     .build();
    }

    private DomainEventData<byte[]> snapshotOf(Class<?> aggregateType, Object payload) {
        return snapshotOf(aggregateType.getSimpleName(), payload);
    }

    private DomainEventData<byte[]> snapshotOf(String storedAggregateType, Object payload) {
        DomainEventMessage<Object> snapshot =
                new GenericDomainEventMessage<>(storedAggregateType, "aggregate-id", 0, payload);
        return new SnapshotEventEntry(snapshot, serializer);
    }

    @Revision(EXPECTED_REVISION)
    private static class AggregateWithExpectedRevision {

    }

    @Revision(OTHER_REVISION)
    private static class AggregateWithOtherRevision {

    }

    @Revision(EXPECTED_REVISION)
    @AggregateRoot(type = "custom-type")
    private static class CustomTypedAggregate {

    }
}
