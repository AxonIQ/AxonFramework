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

package org.axonframework.messaging.eventsourcing.eventstore.jdbc;

import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.messaging.eventhandling.DomainEventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.TrackedEventData;
import org.axonframework.messaging.eventhandling.TrackedEventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventsourcing.eventstore.BatchingEventStorageEngineTest;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.JdbcEventStorageEngineStatements;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.ReadEventDataForAggregateStatementBuilder;
import org.axonframework.eventsourcing.eventstore.jpa.SQLErrorCodesResolver;
import org.axonframework.conversion.json.JacksonSerializer;
import org.hsqldb.jdbc.JDBCDataSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.axonframework.messaging.eventhandling.DomainEventTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link LegacyJdbcEventStorageEngine}.
 *
 * @author Rene de Waele
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection"})
@Testcontainers
@Tags({
        @Tag("slow"),
})
@Disabled("Disabled as part of hefty BlockingStream adjustments")
class JdbcEventStorageEngineIT
        extends BatchingEventStorageEngineTest<LegacyJdbcEventStorageEngine, LegacyJdbcEventStorageEngine.Builder> {

    private JDBCDataSource dataSource;
    private PersistenceExceptionResolver defaultPersistenceExceptionResolver;
    private LegacyJdbcEventStorageEngine testSubject;
    private ReadEventDataForAggregateStatementBuilder readForAggregateStatementBuilder;

    @Container
    private static final HsqldbTestContainer HSQLDB = new HsqldbTestContainer();

    @BeforeEach
    void setUp() throws SQLException {

        dataSource = HSQLDB.getDataSource();

        defaultPersistenceExceptionResolver = new SQLErrorCodesResolver(dataSource);
        //noinspection Convert2Lambda,Anonymous2MethodRef
        readForAggregateStatementBuilder = spy(new ReadEventDataForAggregateStatementBuilder() {
            @Override
            public @NonNull PreparedStatement build(@NonNull Connection connection, @NonNull EventSchema schema,
                                                    @NonNull  String identifier, long firstSequenceNumber, int batchSize) throws SQLException {
                return JdbcEventStorageEngineStatements.readEventDataForAggregate(connection,
                                                                                  schema,
                                                                                  identifier,
                                                                                  firstSequenceNumber,
                                                                                  batchSize);
            }
        });
        setTestSubject(testSubject = createEngine(b -> b.readEventDataForAggregate(readForAggregateStatementBuilder)));
    }

    @Test
    void testLoadLargeAmountOfEventsFromAggregateStream() {
        super.loadLargeAmountOfEventsFromAggregateStream();

        try {
            verify(readForAggregateStatementBuilder, times(6)).build(any(), any(), eq(AGGREGATE), anyLong(), anyInt());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loadLargeAmountOfEventsFromAggregateStream_WithCustomFinalBatchPredicate() throws SQLException {
        setTestSubject(testSubject = createEngine(b -> b.finalAggregateBatchPredicate(l -> l.size() < 50)
                                                        .readEventDataForAggregate(readForAggregateStatementBuilder)));

        super.loadLargeAmountOfEventsFromAggregateStream();

        verify(readForAggregateStatementBuilder, times(4)).build(any(), any(), eq(AGGREGATE), anyLong(), anyInt());
    }

    @Test
    void storeTwoExactSameSnapshots() {
        testSubject.storeSnapshot(createDomainEvent(1));
        testSubject.storeSnapshot(createDomainEvent(1));
    }

    @Test
    void loadLastSequenceNumber() {
        String aggregateId = UUID.randomUUID().toString();
        testSubject.appendEvents(createDomainEvent(aggregateId, 0), createDomainEvent(aggregateId, 1));
        assertEquals(1L, (long) testSubject.lastSequenceNumberFor(aggregateId).orElse(-1L));
        assertFalse(testSubject.lastSequenceNumberFor("inexistent").isPresent());
    }

    @Test
    @DirtiesContext
    void customSchemaConfig() {
        EventSchema testSchema = EventSchema.builder()
                                            .eventTable("CustomDomainEvent")
                                            .payloadColumn("eventData")
                                            .build();
        setTestSubject(testSubject = createEngine(
                engineBuilder -> engineBuilder.schema(testSchema).dataType(String.class),
                new HsqlEventTableFactory() {
                    @Override
                    protected @NonNull String payloadType() {
                        return "LONGVARCHAR";
                    }
                }
        ));

        storeAndLoadEvents();
    }

    @Test
    @DirtiesContext
    void customSchemaConfigTimestampColumn() {
        setTestSubject(testSubject = createTimestampEngine(new HsqlEventTableFactory() {
            @Override
            protected @NonNull String timestampType() {
                return "timestamp";
            }
        }));
        storeAndLoadEvents();
    }

    @Test
    void gapsForVeryOldEventsAreNotIncluded() throws SQLException {
        GenericEventMessage.clock =
                Clock.fixed(Clock.systemUTC().instant().minus(1, ChronoUnit.HOURS), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-1), createDomainEvent(0));

        GenericEventMessage.clock =
                Clock.fixed(Clock.systemUTC().instant().minus(2, ChronoUnit.MINUTES), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-2), createDomainEvent(1));

        GenericEventMessage.clock =
                Clock.fixed(Clock.systemUTC().instant().minus(50, ChronoUnit.SECONDS), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-3), createDomainEvent(2));

        GenericEventMessage.clock = Clock.fixed(Clock.systemUTC().instant(), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-4), createDomainEvent(3));

        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM DomainEventEntry WHERE sequenceNumber < 0").executeUpdate();
        }

        testSubject.fetchTrackedEvents((TrackingToken) null, 100).stream()
                   .map(i -> (GapAwareTrackingToken) i.trackingToken())
                   .forEach(i -> assertTrue(i.getGaps().size() <= 2));
    }

    @DirtiesContext
    @Test
    void oldGapsAreRemovedFromProvidedTrackingToken() throws SQLException {
        testSubject = createEngine(engineBuilder -> engineBuilder.gapTimeout(50001).gapCleaningThreshold(50));

        Instant now = Clock.systemUTC().instant();
        GenericEventMessage.clock = Clock.fixed(now.minus(1, ChronoUnit.HOURS), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-1), createDomainEvent(0)); // index 0 and 1
        GenericEventMessage.clock = Clock.fixed(now.minus(2, ChronoUnit.MINUTES), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-2), createDomainEvent(1)); // index 2 and 3
        GenericEventMessage.clock = Clock.fixed(now.minus(50, ChronoUnit.SECONDS), Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-3), createDomainEvent(2)); // index 4 and 5
        GenericEventMessage.clock = Clock.fixed(now, Clock.systemUTC().getZone());
        testSubject.appendEvents(createDomainEvent(-4), createDomainEvent(3)); // index 6 and 7

        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM DomainEventEntry WHERE sequenceNumber < 0").executeUpdate();
        }

        List<Long> gaps = LongStream.range(-50, 6)
                                    .filter(i -> i != 1L && i != 3L && i != 5)
                                    .boxed()
                                    .collect(Collectors.toList());
        List<? extends TrackedEventData<?>> events =
                testSubject.fetchTrackedEvents(GapAwareTrackingToken.newInstance(6, gaps), 100);
        assertEquals(1, events.size());
        assertEquals(4L, (long) ((GapAwareTrackingToken) events.get(0).trackingToken()).getGaps().first());
    }

    @Test
    void eventsWithUnknownPayloadTypeDoNotResultInError() throws SQLException, InterruptedException {
        String expectedPayloadOne = "Payload3";
        String expectedPayloadTwo = "Payload4";

        int testBatchSize = 2;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));
//        LegacyEmbeddedEventStore testEventStore = LegacyEmbeddedEventStore.builder().storageEngine(testSubject).build();

        testSubject.appendEvents(createDomainEvent(AGGREGATE, 1, "Payload1"),
                                 createDomainEvent(AGGREGATE, 2, "Payload2"));
        // Update events which will be part of the first batch to an unknown payload type
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("UPDATE DomainEventEntry e SET e.payloadType = 'unknown'")
                .executeUpdate();
        }
        testSubject.appendEvents(createDomainEvent(AGGREGATE, 3, expectedPayloadOne),
                                 createDomainEvent(AGGREGATE, 4, expectedPayloadTwo));

        List<String> eventStorageEngineResult = testSubject.readEvents(null, false)
                                                           .filter(m -> m.payload() instanceof String)
                                                           .map(m -> (String) m.payload())
                                                           .collect(toList());
        assertEquals(Arrays.asList(expectedPayloadOne, expectedPayloadTwo), eventStorageEngineResult);

//        TrackingEventStream eventStoreResult = testEventStore.openStream(null);
//        assertTrue(eventStoreResult.hasNextAvailable());
//        assertEquals(UnknownSerializedType.class, eventStoreResult.nextAvailable().payloadType());
//        assertEquals(UnknownSerializedType.class, eventStoreResult.nextAvailable().payloadType());
//        assertEquals(expectedPayloadOne, eventStoreResult.nextAvailable().payload());
//        assertEquals(expectedPayloadTwo, eventStoreResult.nextAvailable().payload());
    }

    @Test
    void streamCrossesConsecutiveGapsOfMoreThanBatchSuccessfully() throws SQLException {
        int testBatchSize = 10;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));
        testSubject.appendEvents(createDomainEvents(100));

        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM DomainEventEntry WHERE globalIndex >= 20 and globalIndex < 40")
                .executeUpdate();
        }

        Stream<? extends TrackedEventMessage> actual = testSubject.readEvents(null, false);
        List<? extends TrackedEventMessage> actualEvents = actual.collect(toList());
        assertEquals(80, actualEvents.size());
    }

    @Test
    void streamDoesNotCrossExtendedGapWhenDisabled() throws SQLException {
        int testBatchSize = 10;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize)
                                                                 .extendedGapCheckEnabled(false));

        try {
            Connection connection = dataSource.getConnection();
            connection.prepareStatement("DROP TABLE IF EXISTS DomainEventEntry").executeUpdate();
            connection.prepareStatement("DROP TABLE IF EXISTS SnapshotEventEntry").executeUpdate();
            testSubject.createSchema(HsqlEventTableFactory.INSTANCE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        testSubject.appendEvents(createDomainEvents(100));

        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM DomainEventEntry WHERE globalIndex >= 20 and globalIndex < 40")
                .executeUpdate();
        }

        Stream<? extends TrackedEventMessage> actual = testSubject.readEvents(null, false);
        List<? extends TrackedEventMessage> actualEvents = actual.collect(toList());
        assertEquals(20, actualEvents.size());
    }

    @Test
    void streamCrossesInitialConsecutiveGapsOfMoreThanBatchSuccessfully() throws SQLException {
        int testBatchSize = 10;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));

        testSubject.appendEvents(createDomainEvents(100));

        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM DomainEventEntry WHERE globalIndex < 20")
                .executeUpdate();
        }

        Stream<? extends TrackedEventMessage> actual = testSubject.readEvents(null, false);
        List<? extends TrackedEventMessage> actualEvents = actual.collect(toList());
        assertEquals(80, actualEvents.size());
    }

    @Test
    void readEventsForAggregateReturnsTheCompleteStream() {
        int testBatchSize = 10;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));

        DomainEventMessage testEventOne = createDomainEvent(0);
        DomainEventMessage testEventTwo = createDomainEvent(1);
        DomainEventMessage testEventThree = createDomainEvent(2);
        DomainEventMessage testEventFour = createDomainEvent(3);
        DomainEventMessage testEventFive = createDomainEvent(4);

        testSubject.appendEvents(testEventOne, testEventTwo, testEventThree, testEventFour, testEventFive);

        List<? extends DomainEventMessage> result = testSubject.readEvents(AGGREGATE, 0L).asStream()
                                                               .collect(toList());

        assertEquals(5, result.size());
        assertEquals(0, result.get(0).getSequenceNumber());
        assertEquals(1, result.get(1).getSequenceNumber());
        assertEquals(2, result.get(2).getSequenceNumber());
        assertEquals(3, result.get(3).getSequenceNumber());
        assertEquals(4, result.get(4).getSequenceNumber());
    }

    @Test
    void readEventsForAggregateWithGapsReturnsTheCompleteStream() {
        int testBatchSize = 10;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));

        DomainEventMessage testEventOne = createDomainEvent(0);
        DomainEventMessage testEventTwo = createDomainEvent(1);
        // Event with sequence number 2 is missing -> the gap
        DomainEventMessage testEventFour = createDomainEvent(3);
        DomainEventMessage testEventFive = createDomainEvent(4);

        testSubject.appendEvents(testEventOne, testEventTwo, testEventFour, testEventFive);

        List<? extends DomainEventMessage> result = testSubject.readEvents(AGGREGATE, 0L).asStream()
                                                               .collect(toList());

        assertEquals(4, result.size());
        assertEquals(0, result.get(0).getSequenceNumber());
        assertEquals(1, result.get(1).getSequenceNumber());
        assertEquals(3, result.get(2).getSequenceNumber());
        assertEquals(4, result.get(3).getSequenceNumber());
    }

    @Test
    void readEventsForAggregateWithEventsExceedingOneBatchReturnsTheCompleteStream() {
        // Set batch size to 5, so that the number of events exceeds at least one batch
        int testBatchSize = 5;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));

        DomainEventMessage testEventOne = createDomainEvent(0);
        DomainEventMessage testEventTwo = createDomainEvent(1);
        DomainEventMessage testEventThree = createDomainEvent(2);
        DomainEventMessage testEventFour = createDomainEvent(3);
        DomainEventMessage testEventFive = createDomainEvent(4);
        DomainEventMessage testEventSix = createDomainEvent(5);
        DomainEventMessage testEventSeven = createDomainEvent(6);
        DomainEventMessage testEventEight = createDomainEvent(7);

        testSubject.appendEvents(
                testEventOne, testEventTwo, testEventThree, testEventFour, testEventFive, testEventSix, testEventSeven,
                testEventEight
        );

        List<? extends DomainEventMessage> result = testSubject.readEvents(AGGREGATE, 0L).asStream()
                                                               .collect(toList());

        assertEquals(8, result.size());
        assertEquals(0, result.get(0).getSequenceNumber());
        assertEquals(1, result.get(1).getSequenceNumber());
        assertEquals(2, result.get(2).getSequenceNumber());
        assertEquals(3, result.get(3).getSequenceNumber());
        assertEquals(4, result.get(4).getSequenceNumber());
        assertEquals(5, result.get(5).getSequenceNumber());
        assertEquals(6, result.get(6).getSequenceNumber());
        assertEquals(7, result.get(7).getSequenceNumber());
    }

    @Test
    void readEventsForAggregateWithEventsExceedingOneBatchAndGapsReturnsTheCompleteStream() {
        // Set batch size to 5, so that the number of events exceeds at least one batch
        int testBatchSize = 5;
        testSubject = createEngine(engineBuilder -> engineBuilder.batchSize(testBatchSize));

        DomainEventMessage testEventOne = createDomainEvent(0);
        DomainEventMessage testEventTwo = createDomainEvent(1);
        // Event with sequence number 2 is missing -> the gap
        DomainEventMessage testEventFour = createDomainEvent(3);
        DomainEventMessage testEventFive = createDomainEvent(4);
        DomainEventMessage testEventSix = createDomainEvent(5);
        DomainEventMessage testEventSeven = createDomainEvent(6);
        DomainEventMessage testEventEight = createDomainEvent(7);

        testSubject.appendEvents(
                testEventOne, testEventTwo, testEventFour, testEventFive, testEventSix, testEventSeven,
                testEventEight
        );

        List<? extends DomainEventMessage> result = testSubject.readEvents(AGGREGATE, 0L).asStream()
                                                               .collect(toList());

        assertEquals(7, result.size());
        assertEquals(0, result.get(0).getSequenceNumber());
        assertEquals(1, result.get(1).getSequenceNumber());
        assertEquals(3, result.get(2).getSequenceNumber());
        assertEquals(4, result.get(3).getSequenceNumber());
        assertEquals(5, result.get(4).getSequenceNumber());
        assertEquals(6, result.get(5).getSequenceNumber());
        assertEquals(7, result.get(6).getSequenceNumber());
    }

    @Override
    protected LegacyJdbcEventStorageEngine createEngine(
            UnaryOperator<LegacyJdbcEventStorageEngine.Builder> customization
    ) {
        return createEngine(customization, HsqlEventTableFactory.INSTANCE);
    }

    private LegacyJdbcEventStorageEngine createEngine(UnaryOperator<LegacyJdbcEventStorageEngine.Builder> customization,
                                                      EventTableFactory eventTableFactory) {
        LegacyJdbcEventStorageEngine.Builder engineBuilder =
                LegacyJdbcEventStorageEngine.builder()
                                            .eventSerializer(JacksonSerializer.defaultSerializer())
                                            .persistenceExceptionResolver(defaultPersistenceExceptionResolver)
                                            .snapshotSerializer(JacksonSerializer.defaultSerializer())
                                            .batchSize(100)
                                            .connectionProvider(dataSource::getConnection)
                                            .transactionManager(NoTransactionManager.INSTANCE);
        return doCreateTables(
                eventTableFactory,
                new LegacyJdbcEventStorageEngine(customization.apply(engineBuilder))
        );
    }

    private LegacyJdbcEventStorageEngine createTimestampEngine(EventTableFactory eventTableFactory) {
        LegacyJdbcEventStorageEngine.Builder builder =
                LegacyJdbcEventStorageEngine.builder()
                                            .eventSerializer(JacksonSerializer.defaultSerializer())
                                            .snapshotSerializer(JacksonSerializer.defaultSerializer())
                                            .connectionProvider(dataSource::getConnection)
                                            .transactionManager(NoTransactionManager.INSTANCE);

        LegacyJdbcEventStorageEngine result = new LegacyJdbcEventStorageEngine(builder) {
            @Override
            protected @NonNull Object readTimeStamp(@NonNull ResultSet resultSet, @NonNull String columnName) throws SQLException {
                Timestamp ts = resultSet.getTimestamp(columnName);
                return ts.toInstant();
            }

            @Override
            protected void writeTimestamp(@NonNull PreparedStatement preparedStatement, int position, @NonNull Instant timestamp)
                    throws SQLException {
                preparedStatement.setTimestamp(position, new Timestamp(timestamp.toEpochMilli()));
            }
        };

        return doCreateTables(eventTableFactory, result);
    }

    private LegacyJdbcEventStorageEngine doCreateTables(EventTableFactory eventTableFactory,
                                                        LegacyJdbcEventStorageEngine result) {
        try {
            Connection connection = dataSource.getConnection();
            connection.prepareStatement("DROP TABLE IF EXISTS DomainEventEntry").executeUpdate();
            connection.prepareStatement("DROP TABLE IF EXISTS SnapshotEventEntry").executeUpdate();
            result.createSchema(eventTableFactory);
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
