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

import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ClockUtils;
import org.axonframework.common.DateTimeUtils;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.JdbcSQLErrorCodesResolver;
import org.axonframework.common.jdbc.JdbcUtils;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.DomainEventData;
import org.axonframework.messaging.eventhandling.DomainEventMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericDomainEventEntry;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.TrackedDomainEventData;
import org.axonframework.messaging.eventhandling.TrackedEventData;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventsourcing.eventstore.BatchingEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.messaging.eventsourcing.eventstore.LegacyEventStorageEngine;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.AppendEventsStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.AppendSnapshotStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.CleanGapsStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.CreateHeadTokenStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.CreateTailTokenStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.CreateTokenAtStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.DeleteSnapshotsStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.FetchTrackedEventsStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.JdbcEventStorageEngineStatements;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.LastSequenceNumberForStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.ReadEventDataForAggregateStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.ReadEventDataWithGapsStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.ReadEventDataWithoutGapsStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.ReadSnapshotDataStatementBuilder;
import org.axonframework.messaging.eventsourcing.eventstore.jdbc.statements.TimestampWriter;
import org.axonframework.messaging.eventsourcing.snapshotting.SnapshotFilter;
import org.axonframework.modelling.ConcurrencyException;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.upcasting.event.EventUpcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.axonframework.common.Assert.isTrue;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.BuilderUtils.assertThat;
import static org.axonframework.common.DateTimeUtils.formatInstant;
import static org.axonframework.common.jdbc.JdbcUtils.*;

/**
 * An {@link LegacyEventStorageEngine} implementation that uses JDBC to store and fetch events.
 * <p>
 * By default, it stores the payload of events as a serialized blob of bytes. It uses other columns to store meta-data
 * that allows quick finding of DomainEvents for a specific aggregate in the correct order.
 * <p>
 * Before using this store make sure the database contains a table named {@link EventSchema#domainEventTable()} and
 * {@link EventSchema#snapshotTable()} in which to store events and snapshots in respectively. For convenience, these
 * tables can be constructed through the {@link LegacyJdbcEventStorageEngine#createSchema(EventTableFactory)}
 * operation.
 *
 * @author Rene de Waele
 * @since 3.0
 * @deprecated In favor of the {@code LegacyJdbcEventStorageEngine}.
 */
@Deprecated(since = "5.0.0", forRemoval = true)
public class LegacyJdbcEventStorageEngine extends BatchingEventStorageEngine {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final int DEFAULT_MAX_GAP_OFFSET = 10000;
    private static final long DEFAULT_LOWEST_GLOBAL_SEQUENCE = 1;
    private static final int DEFAULT_GAP_TIMEOUT = 60000;
    private static final int DEFAULT_GAP_CLEANING_THRESHOLD = 250;
    private static final boolean DEFAULT_EXTENDED_GAP_CHECK_ENABLED = true;

    private final ConnectionProvider connectionProvider;
    private final TransactionManager transactionManager;
    private final Class<?> dataType;
    private final EventSchema schema;
    private final int maxGapOffset;
    private final long lowestGlobalSequence;
    private final boolean extendedGapCheckEnabled;
    private final CreateTokenAtStatementBuilder createTokenAt;
    private final AppendEventsStatementBuilder appendEvents;
    private final LastSequenceNumberForStatementBuilder lastSequenceNumberFor;
    private final CreateTailTokenStatementBuilder createTailToken;
    private final CreateHeadTokenStatementBuilder createHeadToken;
    private final AppendSnapshotStatementBuilder appendSnapshot;
    private final DeleteSnapshotsStatementBuilder deleteSnapshots;
    private final FetchTrackedEventsStatementBuilder fetchTrackedEvents;
    private final CleanGapsStatementBuilder cleanGaps;
    private final ReadEventDataForAggregateStatementBuilder readEventDataForAggregate;
    private final ReadSnapshotDataStatementBuilder readSnapshotData;
    private final ReadEventDataWithoutGapsStatementBuilder readEventDataWithoutGaps;
    private final ReadEventDataWithGapsStatementBuilder readEventDataWithGaps;
    private final int gapTimeout;
    private final int gapCleaningThreshold;

    /**
     * Instantiate a  based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the event and snapshot {@link Serializer}, the {@link ConnectionProvider} and
     * {@link TransactionManager} are not {@code null}, and will throw an {@link AxonConfigurationException} if any of
     * them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a  instance
     */
    protected LegacyJdbcEventStorageEngine(Builder builder) {
        super(builder);
        this.connectionProvider = builder.connectionProvider;
        this.transactionManager = builder.transactionManager;
        this.dataType = builder.dataType;
        this.schema = builder.schema;
        this.lowestGlobalSequence = builder.lowestGlobalSequence;
        this.maxGapOffset = builder.maxGapOffset;
        this.gapTimeout = builder.gapTimeout;
        this.gapCleaningThreshold = builder.gapCleaningThreshold;
        this.extendedGapCheckEnabled = builder.extendedGapCheckEnabled;
        this.createTokenAt = builder.createTokenAt;
        this.appendEvents = builder.appendEvents;
        this.lastSequenceNumberFor = builder.lastSequenceNumberFor;
        this.createTailToken = builder.createTailToken;
        this.createHeadToken = builder.createHeadToken;
        this.appendSnapshot = builder.appendSnapshot;
        this.deleteSnapshots = builder.deleteSnapshots;
        this.fetchTrackedEvents = builder.fetchTrackedEvents;
        this.cleanGaps = builder.cleanGaps;
        this.readEventDataForAggregate = builder.readEventDataForAggregate;
        this.readSnapshotData = builder.readSnapshotData;
        this.readEventDataWithoutGaps = builder.readEventDataWithoutGaps;
        this.readEventDataWithGaps = builder.readEventDataWithGaps;
    }

    /**
     * Instantiate a Builder to be able to create a .
     * <p>
     * The following configurable fields have defaults:
     * <ul>
     * <li>The {@link EventUpcaster} defaults to an {@link org.axonframework.conversion.upcasting.event.NoOpEventUpcaster}.</li>
     * <li>The {@link PersistenceExceptionResolver} is defaulted to a {@link JdbcSQLErrorCodesResolver}</li>
     * <li>The {@code snapshotFilter} defaults to a {@link SnapshotFilter#allowAll()} instance.</li>
     * <li>The {@code batchSize} defaults to an integer of size {@code 100}.</li>
     * <li>The {@code dataType} is defaulted to the {@code byte[]} type.</li>
     * <li>The {@link EventSchema} defaults to an {@link EventSchema#EventSchema()} call.</li>
     * <li>The {@code maxGapOffset} defaults to an  integer of size {@code 10000}.</li>
     * <li>The {@code lowestGlobalSequence} defaults to a long of size {@code 1}.</li>
     * <li>The {@code gapTimeout} defaults to an integer of size {@code 60000} (1 minute).</li>
     * <li>The {@code gapCleaningThreshold} defaults to an integer of size {@code 250}.</li>
     * <li>The {@code extendedGapCheckEnabled} defaults to {@code true}.</li>
     * <li>The {@code createTokenAt} defaults to {@link JdbcEventStorageEngineStatements#createTokenAt}.</li>
     * <li>The {@code appendEvents} defaults to {@link JdbcEventStorageEngineStatements#appendEvents}.</li>
     * <li>The {@code lastSequenceNumberFor} defaults to {@link JdbcEventStorageEngineStatements#lastSequenceNumberFor}.</li>
     * <li>The {@code createTailToken} defaults to {@link JdbcEventStorageEngineStatements#createTailToken}.</li>
     * <li>The {@code createHeadToken} defaults to {@link JdbcEventStorageEngineStatements#createHeadToken}.</li>
     * <li>The {@code appendSnapshot} defaults to {@link JdbcEventStorageEngineStatements#appendSnapshot}.</li>
     * <li>The {@code deleteSnapshots} defaults to {@link JdbcEventStorageEngineStatements#deleteSnapshots}.</li>
     * <li>The {@code fetchTrackedEvents} defaults to {@link JdbcEventStorageEngineStatements#fetchTrackedEvents}.</li>
     * <li>The {@code cleanGaps} defaults to {@link JdbcEventStorageEngineStatements#cleanGaps}.</li>
     * <li>The {@code readEventDataForAggregate} defaults to {@link JdbcEventStorageEngineStatements#readEventDataForAggregate}.</li>
     * <li>The {@code readSnapshotData} defaults to {@link JdbcEventStorageEngineStatements#readSnapshotData}.</li>
     * <li>The {@code readEventDataWithoutGaps} defaults to {@link JdbcEventStorageEngineStatements#readEventDataWithoutGaps}.</li>
     * <li>The {@code readEventDataWithGaps} defaults to {@link JdbcEventStorageEngineStatements#readEventDataWithGaps}.</li>
     * </ul>
     * <p>
     * The event and snapshot {@link Serializer}, {@link ConnectionProvider} and {@link TransactionManager} are <b>hard
     * requirements</b> and as such should be provided.
     *
     * @return a Builder to be able to create a
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#createTokenAt(Instant)}.
     *
     * @param connection The connection to the database.
     * @param dateTime   The dateTime where the token will be created.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement createTokenAt(Connection connection, Instant dateTime) throws SQLException {
        return createTokenAt.build(connection, schema, dateTime);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#appendEvents(List, Serializer)}.
     *
     * @param connection The connection to the database.
     * @param events     The events to be added.
     * @param serializer The serializer for the payload and metadata.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement appendEvents(Connection connection, List<? extends EventMessage> events,
                                             Serializer serializer) throws SQLException {
        return appendEvents.build(connection, schema, dataType, events, serializer, this::writeTimestamp);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#lastSequenceNumberFor(String)}.
     *
     * @param connection          The connection to the database.
     * @param aggregateIdentifier The identifier of the aggregate.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement lastSequenceNumberFor(Connection connection, String aggregateIdentifier)
            throws SQLException {
        return lastSequenceNumberFor.build(connection, schema, aggregateIdentifier);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#createTailToken()}.
     *
     * @param connection The connection to the database.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement createTailToken(Connection connection) throws SQLException {
        return createTailToken.build(connection, schema);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#createHeadToken()}.
     *
     * @param connection The connection to the database.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement createHeadToken(Connection connection) throws SQLException {
        return createHeadToken.build(connection, schema);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#storeSnapshot(DomainEventMessage)}.
     *
     * @param connection The connection to the database.
     * @param snapshot   The snapshot to be appended.
     * @param serializer The serializer for the payload and metadata.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement appendSnapshot(Connection connection, DomainEventMessage snapshot,
                                               Serializer serializer) throws SQLException {
        return appendSnapshot.build(connection, schema, dataType, snapshot, serializer, this::writeTimestamp);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#storeSnapshot(DomainEventMessage)}.
     *
     * @param connection          The connection to the database.
     * @param aggregateIdentifier The identifier of the aggregate taken from the snapshot.
     * @param sequenceNumber      The sequence number taken from the snapshot.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement deleteSnapshots(Connection connection, String aggregateIdentifier, long sequenceNumber)
            throws SQLException {
        return deleteSnapshots.build(connection, schema, aggregateIdentifier, sequenceNumber);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#fetchTrackedEvents(TrackingToken, int)}.
     *
     * @param connection The connection to the database.
     * @param index      The index taken from the tracking token.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement fetchTrackedEvents(Connection connection, long index) throws SQLException {
        return fetchTrackedEvents.build(connection, schema, index);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#cleanGaps(TrackingToken)}.
     *
     * @param connection The connection to the database.
     * @param gaps       The Set of gaps taken from the tracking token.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement cleanGaps(Connection connection, SortedSet<Long> gaps) throws SQLException {
        return cleanGaps.build(connection, schema, gaps);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#fetchDomainEvents(String, long, int)}
     *
     * @param connection          The connection to the database.
     * @param identifier          The identifier of the aggregate.
     * @param firstSequenceNumber The expected sequence number of the first returned entry.
     * @param batchSize           The number of items to include in the batch.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement readEventData(Connection connection, String identifier, long firstSequenceNumber,
                                              int batchSize) throws SQLException {
        return readEventDataForAggregate.build(connection, schema, identifier, firstSequenceNumber, batchSize);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#readSnapshotData(String)}.
     *
     * @param connection The connection to the database.
     * @param identifier The identifier of the aggregate.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement readSnapshotData(Connection connection, String identifier) throws SQLException {
        return readSnapshotData.build(connection, schema, identifier);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#fetchTrackedEvents(TrackingToken, int)}
     *
     * @param connection  The connection to the database.
     * @param globalIndex The index taken from the tracking token.
     * @param batchSize   The number of items to include in the batch
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement readEventDataWithoutGaps(Connection connection, long globalIndex, int batchSize)
            throws SQLException {
        return readEventDataWithoutGaps.build(connection, schema, globalIndex, batchSize);
    }

    /**
     * Creates a statement to be used at {@link LegacyJdbcEventStorageEngine#fetchTrackedEvents(TrackingToken, int)}
     *
     * @param connection  The connection to the database.
     * @param globalIndex The index taken from the tracking token.
     * @param batchSize   The number of items to include in the batch
     * @param gaps        The Set of gaps taken from the tracking token.
     * @return The newly created {@link PreparedStatement}.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement readEventDataWithGaps(Connection connection, long globalIndex, int batchSize,
                                                      List<Long> gaps) throws SQLException {
        return readEventDataWithGaps.build(connection, schema, globalIndex, batchSize, gaps);
    }

    /**
     * Performs the DDL queries to create the schema necessary for this storage engine implementation.
     *
     * @param schemaFactory Factory of the event schema.
     * @throws EventStoreException when an error occurs executing SQL statements.
     */
    public void createSchema(EventTableFactory schemaFactory) {
        executeUpdates(getConnection(), e -> {
                           throw new EventStoreException("Failed to create event tables", e);
                       }, connection -> schemaFactory.createDomainEventTable(connection, schema),
                       connection -> schemaFactory.createSnapshotEventTable(connection, schema));
    }

    @Override
    protected void appendEvents(List<? extends EventMessage> events, Serializer serializer) {
        if (events.isEmpty()) {
            return;
        }
        transactionManager.executeInTransaction(
                () -> executeBatch(
                        getConnection(),
                        connection -> appendEvents(connection, events, serializer),
                        e -> handlePersistenceException(e, events.get(0))));
    }

    @Override
    protected void storeSnapshot(DomainEventMessage snapshot, Serializer serializer) {
        transactionManager.executeInTransaction(() -> {
            try {
                executeUpdates(
                        getConnection(), e -> handlePersistenceException(e, snapshot),
                        connection -> appendSnapshot(connection, snapshot, serializer),
                        connection -> deleteSnapshots(connection,
                                                      snapshot.getAggregateIdentifier(),
                                                      snapshot.getSequenceNumber())
                );
            } catch (ConcurrencyException e) {
                // Ignore duplicate key issues in snapshot. It just means a snapshot already exists
            }
        });
    }

    @Override
    public Optional<Long> lastSequenceNumberFor(String aggregateIdentifier) {
        return Optional.ofNullable(transactionManager.fetchInTransaction(
                () -> executeQuery(getConnection(),
                                   connection -> lastSequenceNumberFor(connection, aggregateIdentifier),
                                   resultSet -> nextAndExtract(resultSet, 1, Long.class),
                                   e -> new EventStoreException(
                                           format("Failed to read events for aggregate [%s]", aggregateIdentifier), e
                                   )
                )));
    }

    @Override
    public TrackingToken createTailToken() {
        Long index = transactionManager.fetchInTransaction(() -> executeQuery(
                getConnection(),
                this::createTailToken,
                resultSet -> nextAndExtract(resultSet, 1, Long.class),
                e -> new EventStoreException("Failed to get tail token", e)
        ));
        return createToken(index);
    }

    @Override
    public TrackingToken createHeadToken() {
        return createToken(mostRecentIndex());
    }

    @Override
    public TrackingToken createTokenAt(Instant dateTime) {
        Long index = transactionManager.fetchInTransaction(() -> executeQuery(
                getConnection(),
                connection -> createTokenAt(connection, dateTime),
                resultSet -> nextAndExtract(resultSet, 1, Long.class),
                e -> new EventStoreException(format("Failed to get token at [%s]", dateTime), e)
        ));
        return index != null ? createToken(index) : createToken(mostRecentIndex());
    }

    private Long mostRecentIndex() {
        return transactionManager.fetchInTransaction(() -> executeQuery(
                getConnection(),
                this::createHeadToken,
                resultSet -> nextAndExtract(resultSet, 1, Long.class),
                e -> new EventStoreException("Failed to get head token", e)
        ));
    }

    private TrackingToken createToken(Long index) {
        return Optional.ofNullable(index)
                       .map(seq -> GapAwareTrackingToken.newInstance(seq, Collections.emptySet()))
                       .orElse(null);
    }

    @Override
    protected List<? extends DomainEventData<?>> fetchDomainEvents(String aggregateIdentifier, long firstSequenceNumber,
                                                                   int batchSize) {
        return transactionManager.fetchInTransaction(
                () -> executeQuery(
                        getConnection(),
                        connection -> readEventData(connection, aggregateIdentifier, firstSequenceNumber, batchSize),
                        JdbcUtils.listResults(this::getDomainEventData),
                        e -> new EventStoreException(
                                format("Failed to read events for aggregate [%s]", aggregateIdentifier), e
                        )
                ));
    }

    @Override
    protected boolean fetchForAggregateUntilEmpty() {
        return true;
    }

    @Override
    protected List<? extends TrackedEventData<?>> fetchTrackedEvents(TrackingToken lastToken, int batchSize) {
        Assert.isTrue(
                lastToken == null || lastToken instanceof GapAwareTrackingToken,
                () -> String.format("Token [%s] is of the wrong type. Expected [%s]",
                                    lastToken, GapAwareTrackingToken.class.getSimpleName())
        );

        return transactionManager.fetchInTransaction(() -> {
            // If there are many gaps, it worthwhile checking if it is possible to clean them up.
            GapAwareTrackingToken cleanedToken;
            if (lastToken != null && ((GapAwareTrackingToken) lastToken).getGaps().size() > gapCleaningThreshold) {
                cleanedToken = cleanGaps(lastToken);
            } else {
                cleanedToken = (GapAwareTrackingToken) lastToken;
            }

            List<TrackedEventData<?>> eventData = executeEventDataQuery(cleanedToken, batchSize);

            // Additional check for empty batches. This may be because there is a gap of more than _batchSize_ items ahead
            // see
            if (extendedGapCheckEnabled && eventData.isEmpty()) {
                long index = cleanedToken == null ? -1 : cleanedToken.getIndex();
                Long result = executeQuery(getConnection(),
                                           connection -> fetchTrackedEvents(connection, index),
                                           resultSet -> nextAndExtract(resultSet, 1, Long.class),
                                           e -> new EventStoreException("Failed to read globalIndex ahead of token",
                                                                        e));
                if (result != null) {
                    return executeEventDataQuery(cleanedToken, (int) (result - index));
                }
            }
            return eventData;
        });
    }

    private List<TrackedEventData<?>> executeEventDataQuery(GapAwareTrackingToken cleanedToken, int batchSize) {
        return executeQuery(
                getConnection(),
                connection -> readEventData(connection, cleanedToken, batchSize),
                resultSet -> {
                    GapAwareTrackingToken previousToken = cleanedToken;
                    List<TrackedEventData<?>> results = new ArrayList<>();
                    while (resultSet.next()) {
                        TrackedEventData<?> next = getTrackedEventData(resultSet, previousToken);
                        results.add(next);
                        previousToken = (GapAwareTrackingToken) next.trackingToken();
                    }
                    return results;
                },
                e -> new EventStoreException(format("Failed to read events from token [%s]", cleanedToken), e)
        );
    }

    private GapAwareTrackingToken cleanGaps(TrackingToken lastToken) {
        SortedSet<Long> gaps = ((GapAwareTrackingToken) lastToken).getGaps();
        return executeQuery(
                getConnection(),
                connection -> cleanGaps(connection, gaps),
                resultSet -> {
                    GapAwareTrackingToken cleanToken = (GapAwareTrackingToken) lastToken;
                    while (resultSet.next()) {
                        try {
                            long sequenceNumber = resultSet.getLong(schema.globalIndexColumn());
                            Instant timestamp =
                                    DateTimeUtils.parseInstant(readTimeStamp(resultSet,
                                                                             schema.timestampColumn())
                                                                       .toString());
                            if (gaps.contains(sequenceNumber) || timestamp.isAfter(gapTimeoutFrame())) {
                                // Filled a gap, should not continue cleaning up.
                                break;
                            }
                            if (gaps.contains(sequenceNumber - 1)) {
                                cleanToken = cleanToken.withGapsTruncatedAt(sequenceNumber);
                            }
                        } catch (DateTimeParseException e) {
                            logger.info(
                                    "Unable to parse timestamp to clean old gaps. Tokens may contain large numbers of gaps, decreasing Tracking performance.");
                            break;
                        }
                    }
                    return cleanToken;
                },
                e -> new EventStoreException(format("Failed to read events from token [%s]", lastToken),
                                             e));
    }

    @Override
    protected Stream<? extends DomainEventData<?>> readSnapshotData(String aggregateIdentifier) {
        return transactionManager.fetchInTransaction(() -> {
            List<DomainEventData<?>> result =
                    executeQuery(
                            getConnection(),
                            connection -> readSnapshotData(connection, aggregateIdentifier),
                            JdbcUtils.listResults(this::getSnapshotData), e -> new EventStoreException(
                                    format("Error reading aggregate snapshot [%s]", aggregateIdentifier), e));
            return result.stream();
        });
    }

    /**
     * Creates a statement to read all tracked event entries stored.
     *
     * @param connection The connection to the database.
     * @param batchSize  The number of items to include in the batch
     * @return A {@link PreparedStatement} that returns event entries for the given query when executed.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    private PreparedStatement readEventDataWithoutToken(Connection connection, int batchSize) throws SQLException {
        return readEventDataWithoutGaps(connection, -1, batchSize);
    }

    /**
     * Creates a statement to read tracked event entries stored since given tracking token. Pass a {@code trackingToken}
     * of {@code null} to create a statement for all entries in the storage.
     *
     * @param connection The connection to the database.
     * @param lastToken  Object describing the global index of the last processed event or {@code null} to return all
     *                   entries in the store.
     * @param batchSize  The number of items to include in the batch
     * @return A {@link PreparedStatement} that returns event entries for the given query when executed.
     * @throws SQLException when an exception occurs while creating the prepared statement.
     */
    protected PreparedStatement readEventData(Connection connection, TrackingToken lastToken, int batchSize)
            throws SQLException {
        isTrue(lastToken == null || lastToken instanceof GapAwareTrackingToken,
               () -> format("Token [%s] is of the wrong type", lastToken));
        GapAwareTrackingToken previousToken = (GapAwareTrackingToken) lastToken;

        if (previousToken == null) {
            return readEventDataWithoutToken(connection, batchSize);
        }

        List<Long> gaps = new ArrayList<>(previousToken.getGaps());
        long globalIndex = previousToken.getIndex();
        if (gaps.isEmpty()) {
            return readEventDataWithoutGaps(connection, globalIndex, batchSize);
        }

        return readEventDataWithGaps(connection, globalIndex, batchSize, gaps);
    }

    /**
     * Extracts the next tracked event entry from the given {@code resultSet}.
     *
     * @param resultSet     The results of a query for tracked events.
     * @param previousToken The last known token of the tracker before obtaining this result set.
     * @return The next tracked event.
     * @throws SQLException when an exception occurs while creating the event data.
     */
    protected TrackedEventData<?> getTrackedEventData(ResultSet resultSet,
                                                      GapAwareTrackingToken previousToken) throws SQLException {
        long globalSequence = resultSet.getLong(schema.globalIndexColumn());

        String aggregateIdentifier = resultSet.getString(schema.aggregateIdentifierColumn());
        String eventIdentifier = resultSet.getString(schema.eventIdentifierColumn());
        GenericDomainEventEntry<?> domainEvent = new GenericDomainEventEntry<>(
                resultSet.getString(schema.typeColumn()),
                eventIdentifier.equals(aggregateIdentifier) ? null : aggregateIdentifier,
                resultSet.getLong(schema.sequenceNumberColumn()),
                eventIdentifier,
                readTimeStamp(resultSet, schema.timestampColumn()),
                resultSet.getString(schema.payloadTypeColumn()),
                resultSet.getString(schema.payloadRevisionColumn()),
                readPayload(resultSet, schema.payloadColumn()),
                readPayload(resultSet, schema.metadataColumn())
        );

        // Now that we have the event itself, we can calculate the token.
        boolean allowGaps = domainEvent.getTimestamp().isAfter(gapTimeoutFrame());
        GapAwareTrackingToken token = previousToken;
        if (token == null) {
            token = GapAwareTrackingToken.newInstance(
                    globalSequence,
                    allowGaps
                            ? LongStream.range(Math.min(lowestGlobalSequence, globalSequence), globalSequence)
                                        .boxed()
                                        .collect(Collectors.toCollection(TreeSet::new))
                            : Collections.emptySortedSet()
            );
        } else {
            token = token.advanceTo(globalSequence, allowGaps ? maxGapOffset : 0);
        }
        return new TrackedDomainEventData<>(token, domainEvent);
    }

    private Instant gapTimeoutFrame() {
        return ClockUtils.instant().minus(gapTimeout, ChronoUnit.MILLIS);
    }

    /**
     * Extracts the next domain event entry from the given {@code resultSet}.
     *
     * @param resultSet The results of a query for domain events of an aggregate.
     * @return The next domain event.
     * @throws SQLException when an exception occurs while creating the event data.
     */
    protected DomainEventData<?> getDomainEventData(ResultSet resultSet) throws SQLException {
        return new GenericDomainEventEntry<>(resultSet.getString(schema.typeColumn()),
                                             resultSet.getString(schema.aggregateIdentifierColumn()),
                                             resultSet.getLong(schema.sequenceNumberColumn()),
                                             resultSet.getString(schema.eventIdentifierColumn()),
                                             readTimeStamp(resultSet, schema.timestampColumn()),
                                             resultSet.getString(schema.payloadTypeColumn()),
                                             resultSet.getString(schema.payloadRevisionColumn()),
                                             readPayload(resultSet, schema.payloadColumn()),
                                             readPayload(resultSet, schema.metadataColumn()));
    }

    /**
     * Extracts the next snapshot entry from the given {@code resultSet}.
     *
     * @param resultSet The results of a query for a snapshot of an aggregate.
     * @return The next snapshot data.
     * @throws SQLException when an exception occurs while creating the event data.
     */
    protected DomainEventData<?> getSnapshotData(ResultSet resultSet) throws SQLException {
        return new GenericDomainEventEntry<>(resultSet.getString(schema.typeColumn()),
                                             resultSet.getString(schema.aggregateIdentifierColumn()),
                                             resultSet.getLong(schema.sequenceNumberColumn()),
                                             resultSet.getString(schema.eventIdentifierColumn()),
                                             readTimeStamp(resultSet, schema.timestampColumn()),
                                             resultSet.getString(schema.payloadTypeColumn()),
                                             resultSet.getString(schema.payloadRevisionColumn()),
                                             readPayload(resultSet, schema.payloadColumn()),
                                             readPayload(resultSet, schema.metadataColumn()));
    }

    /**
     * Reads a timestamp from the given {@code resultSet} at given {@code columnIndex}. The resultSet is positioned in
     * the row that contains the data. This method must not change the row in the result set.
     *
     * @param resultSet  The resultSet containing the stored data.
     * @param columnName The name of the column containing the timestamp.
     * @return an object describing the timestamp.
     * @throws SQLException when an exception occurs reading from the resultSet.
     */
    protected Object readTimeStamp(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getString(columnName);
    }

    /**
     * Write a timestamp from a {@link Instant} to a data value suitable for the database scheme.
     *
     * @param preparedStatement the statement to update.
     * @param position          the position of the timestamp parameter in the statement.
     * @param timestamp         {@link Instant} to convert.
     * @throws SQLException if modification of the statement fails.
     */
    protected void writeTimestamp(PreparedStatement preparedStatement, int position,
                                  Instant timestamp) throws SQLException {
        preparedStatement.setString(position, formatInstant(timestamp));
    }

    /**
     * Reads a serialized object from the given {@code resultSet} at given {@code columnIndex}. The resultSet is
     * positioned in the row that contains the data. This method must not change the row in the result set.
     *
     * @param resultSet  The resultSet containing the stored data.
     * @param columnName The name of the column containing the payload.
     * @return an object describing the serialized data.
     * @throws SQLException when an exception occurs reading from the resultSet.
     */
    @SuppressWarnings("unchecked")
    protected <T> T readPayload(ResultSet resultSet, String columnName) throws SQLException {
        if (byte[].class.equals(dataType)) {
            return (T) resultSet.getBytes(columnName);
        }
        return (T) resultSet.getObject(columnName);
    }

    /**
     * Returns the {@link EventSchema} that defines the table and column names of event tables in the database.
     *
     * @return the event schema.
     */
    protected EventSchema schema() {
        return schema;
    }

    /**
     * Returns a {@link Connection} to the database.
     *
     * @return a database Connection.
     */
    protected Connection getConnection() {
        try {
            return connectionProvider.getConnection();
        } catch (SQLException e) {
            throw new EventStoreException("Failed to obtain a database connection", e);
        }
    }

    /**
     * Builder class to instantiate a {@link LegacyJdbcEventStorageEngine}.
     * <p>
     * The following configurable fields have defaults:
     * <ul>
     * <li>The {@link EventUpcaster} defaults to an {@link org.axonframework.conversion.upcasting.event.NoOpEventUpcaster}.</li>
     * <li>The {@link PersistenceExceptionResolver} is defaulted to a {@link JdbcSQLErrorCodesResolver}</li>
     * <li>The {@code snapshotFilter} defaults to a {@link SnapshotFilter#allowAll()} instance.</li>
     * <li>The {@code batchSize} defaults to an integer of size {@code 100}.</li>
     * <li>The {@code dataType} is defaulted to the {@code byte[]} type.</li>
     * <li>The {@link EventSchema} defaults to an {@link EventSchema#EventSchema()} call.</li>
     * <li>The {@code maxGapOffset} defaults to an  integer of size {@code 10000}.</li>
     * <li>The {@code lowestGlobalSequence} defaults to a long of size {@code 1}.</li>
     * <li>The {@code gapTimeout} defaults to an integer of size {@code 60000} (1 minute).</li>
     * <li>The {@code gapCleaningThreshold} defaults to an integer of size {@code 250}.</li>
     * <li>The {@code extendedGapCheckEnabled} defaults to {@code true}.</li>
     * <li>The {@code createTokenAt} defaults to {@link JdbcEventStorageEngineStatements#createTokenAt}.</li>
     * <li>The {@code appendEvents} defaults to {@link JdbcEventStorageEngineStatements#appendEvents}.</li>
     * <li>The {@code lastSequenceNumberFor} defaults to {@link JdbcEventStorageEngineStatements#lastSequenceNumberFor}.</li>
     * <li>The {@code createTailToken} defaults to {@link JdbcEventStorageEngineStatements#createTailToken}.</li>
     * <li>The {@code createHeadToken} defaults to {@link JdbcEventStorageEngineStatements#createHeadToken}.</li>
     * <li>The {@code appendSnapshot} defaults to {@link JdbcEventStorageEngineStatements#appendSnapshot}.</li>
     * <li>The {@code deleteSnapshots} defaults to {@link JdbcEventStorageEngineStatements#deleteSnapshots}.</li>
     * <li>The {@code fetchTrackedEvents} defaults to {@link JdbcEventStorageEngineStatements#fetchTrackedEvents}.</li>
     * <li>The {@code cleanGaps} defaults to {@link JdbcEventStorageEngineStatements#cleanGaps}.</li>
     * <li>The {@code readEventDataForAggregate} defaults to {@link JdbcEventStorageEngineStatements#readEventDataForAggregate}.</li>
     * <li>The {@code readSnapshotData} defaults to {@link JdbcEventStorageEngineStatements#readSnapshotData}.</li>
     * <li>The {@code readEventDataWithoutGaps} defaults to {@link JdbcEventStorageEngineStatements#readEventDataWithoutGaps}.</li>
     * <li>The {@code readEventDataWithGaps} defaults to {@link JdbcEventStorageEngineStatements#readEventDataWithGaps}.</li>
     * </ul>
     * <p>
     * The event and snapshot {@link Serializer}, {@link ConnectionProvider} and {@link TransactionManager} are <b>hard
     * requirements</b> and as such should be provided.
     */
    public static class Builder extends BatchingEventStorageEngine.Builder {

        private ConnectionProvider connectionProvider;
        private TransactionManager transactionManager;
        private Class<?> dataType = byte[].class;
        private EventSchema schema = new EventSchema();
        private int maxGapOffset = DEFAULT_MAX_GAP_OFFSET;
        private long lowestGlobalSequence = DEFAULT_LOWEST_GLOBAL_SEQUENCE;
        private int gapTimeout = DEFAULT_GAP_TIMEOUT;
        private int gapCleaningThreshold = DEFAULT_GAP_CLEANING_THRESHOLD;
        private boolean extendedGapCheckEnabled = DEFAULT_EXTENDED_GAP_CHECK_ENABLED;

        private CreateTokenAtStatementBuilder createTokenAt = JdbcEventStorageEngineStatements::createTokenAt;
        private AppendEventsStatementBuilder appendEvents = JdbcEventStorageEngineStatements::appendEvents;
        private LastSequenceNumberForStatementBuilder lastSequenceNumberFor = JdbcEventStorageEngineStatements::lastSequenceNumberFor;
        private CreateTailTokenStatementBuilder createTailToken = JdbcEventStorageEngineStatements::createTailToken;
        private CreateHeadTokenStatementBuilder createHeadToken = JdbcEventStorageEngineStatements::createHeadToken;
        private AppendSnapshotStatementBuilder appendSnapshot = JdbcEventStorageEngineStatements::appendSnapshot;
        private DeleteSnapshotsStatementBuilder deleteSnapshots = JdbcEventStorageEngineStatements::deleteSnapshots;
        private FetchTrackedEventsStatementBuilder fetchTrackedEvents = JdbcEventStorageEngineStatements::fetchTrackedEvents;
        private CleanGapsStatementBuilder cleanGaps = JdbcEventStorageEngineStatements::cleanGaps;
        private ReadEventDataForAggregateStatementBuilder readEventDataForAggregate = JdbcEventStorageEngineStatements::readEventDataForAggregate;
        private ReadSnapshotDataStatementBuilder readSnapshotData = JdbcEventStorageEngineStatements::readSnapshotData;
        private ReadEventDataWithoutGapsStatementBuilder readEventDataWithoutGaps = JdbcEventStorageEngineStatements::readEventDataWithoutGaps;
        private ReadEventDataWithGapsStatementBuilder readEventDataWithGaps = JdbcEventStorageEngineStatements::readEventDataWithGaps;

        private Builder() {
            persistenceExceptionResolver(new JdbcSQLErrorCodesResolver());
        }

        /**
         * Set the PreparedStatement to be used on {@link LegacyJdbcEventStorageEngine#createTokenAt}. Defaults to
         * {@link JdbcEventStorageEngineStatements#createTokenAt(Connection, EventSchema, Instant)}.
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder createTokenAt(CreateTokenAtStatementBuilder createTokenAt) {
            assertNonNull(createTokenAt, "createTokenAt may not be null");
            this.createTokenAt = createTokenAt;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#appendEvents(Connection, List, Serializer)} en}. Defaults to
         * {@link JdbcEventStorageEngineStatements#appendEvents(Connection, EventSchema, Class, List, Serializer,
         * TimestampWriter)}.
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder appendEvents(AppendEventsStatementBuilder appendEvents) {
            assertNonNull(appendEvents, "appendEvents may not be null");
            this.appendEvents = appendEvents;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#lastSequenceNumberFor(Connection, String)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#lastSequenceNumberFor(Connection, EventSchema, String)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder lastSequenceNumberFor(LastSequenceNumberForStatementBuilder lastSequenceNumberFor) {
            assertNonNull(lastSequenceNumberFor, "lastSequenceNumberFor may not be null");
            this.lastSequenceNumberFor = lastSequenceNumberFor;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on {@link LegacyJdbcEventStorageEngine#createTailToken(Connection)}.
         * Defaults to {@link JdbcEventStorageEngineStatements#createTailToken(Connection, EventSchema)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder createTailToken(CreateTailTokenStatementBuilder createTailToken) {
            assertNonNull(createTailToken, "createTailToken may not be null");
            this.createTailToken = createTailToken;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on {@link LegacyJdbcEventStorageEngine#createHeadToken(Connection)}.
         * Defaults to {@link JdbcEventStorageEngineStatements#createHeadToken(Connection, EventSchema)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder createHeadToken(CreateHeadTokenStatementBuilder createHeadToken) {
            assertNonNull(createHeadToken, "createHeadToken may not be null");
            this.createHeadToken = createHeadToken;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#appendSnapshot(Connection, DomainEventMessage, Serializer)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#appendEvents(Connection, EventSchema, Class, List, Serializer,
         * TimestampWriter)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder appendSnapshot(AppendSnapshotStatementBuilder appendSnapshot) {
            assertNonNull(appendSnapshot, "appendSnapshot may not be null");
            this.appendSnapshot = appendSnapshot;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#deleteSnapshots(Connection, String, long)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#deleteSnapshots(Connection, EventSchema, String, long)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder deleteSnapshots(DeleteSnapshotsStatementBuilder deleteSnapshots) {
            assertNonNull(deleteSnapshots, "deleteSnapshots may not be null");
            this.deleteSnapshots = deleteSnapshots;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#fetchTrackedEvents(Connection, long)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#fetchTrackedEvents(Connection, EventSchema, long)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder fetchTrackedEvents(FetchTrackedEventsStatementBuilder fetchTrackedEvents) {
            assertNonNull(fetchTrackedEvents, "fetchTrackedEvents may not be null");
            this.fetchTrackedEvents = fetchTrackedEvents;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#cleanGaps(Connection, SortedSet)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#cleanGaps(Connection, EventSchema, SortedSet)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder cleanGaps(CleanGapsStatementBuilder cleanGaps) {
            assertNonNull(cleanGaps, "cleanGaps may not be null");
            this.cleanGaps = cleanGaps;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#readEventData(Connection, String, long, int)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#readEventDataForAggregate(Connection, EventSchema, String, long,
         * int)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder readEventDataForAggregate(ReadEventDataForAggregateStatementBuilder readEventDataForAggregate) {
            assertNonNull(readEventDataForAggregate, "readEventDataForAggregate may not be null");
            this.readEventDataForAggregate = readEventDataForAggregate;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#readSnapshotData(Connection, String)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#readSnapshotData(Connection, EventSchema, String)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder readSnapshotData(ReadSnapshotDataStatementBuilder readSnapshotData) {
            assertNonNull(readSnapshotData, "readSnapshotData may not be null");
            this.readSnapshotData = readSnapshotData;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#readEventDataWithoutGaps(Connection, long, int)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#readEventDataWithoutGaps(Connection, EventSchema, long, int)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder readEventDataWithoutGaps(ReadEventDataWithoutGapsStatementBuilder readEventDataWithoutGaps) {
            assertNonNull(readEventDataWithoutGaps, "readEventDataWithoutGaps may not be null");
            this.readEventDataWithoutGaps = readEventDataWithoutGaps;
            return this;
        }

        /**
         * Set the PreparedStatement to be used on
         * {@link LegacyJdbcEventStorageEngine#readEventDataWithGaps(Connection, long, int, List)}. Defaults to
         * {@link JdbcEventStorageEngineStatements#readEventDataWithGaps(Connection, EventSchema, long, int, List)}
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder readEventDataWithGaps(ReadEventDataWithGapsStatementBuilder readEventDataWithGaps) {
            assertNonNull(readEventDataWithGaps, "readEventDataWithGaps may not be null");
            this.readEventDataWithGaps = readEventDataWithGaps;
            return this;
        }

        @Override
        public LegacyJdbcEventStorageEngine.Builder snapshotSerializer(Serializer snapshotSerializer) {
            super.snapshotSerializer(snapshotSerializer);
            return this;
        }

//        @Override
//        public LegacyJdbcEventStorageEngine.Builder upcasterChain(EventUpcaster upcasterChain) {
//            super.upcasterChain(upcasterChain);
//            return this;
//        }

        /**
         * {@inheritDoc} Defaults to a {@link JdbcSQLErrorCodesResolver}.
         */
        @Override
        public LegacyJdbcEventStorageEngine.Builder persistenceExceptionResolver(
                PersistenceExceptionResolver persistenceExceptionResolver
        ) {
            super.persistenceExceptionResolver(persistenceExceptionResolver);
            return this;
        }

        @Override
        public LegacyJdbcEventStorageEngine.Builder eventSerializer(Serializer eventSerializer) {
            super.eventSerializer(eventSerializer);
            return this;
        }

        /**
         * {@inheritDoc}
         * <p>
         * The JdbcEventStorageEngine defaults to using an empty batch as the final batch. While this is safe, it is
         * also relatively inefficient. When one can guarantee that no events for an aggregate are every removed from
         * the event store, any batch smaller than the maximum batch size can be seen as the final batch.
         */
        @Override
        public LegacyJdbcEventStorageEngine.Builder finalAggregateBatchPredicate(
                Predicate<List<? extends DomainEventData<?>>> finalAggregateBatchPredicate) {
            super.finalAggregateBatchPredicate(finalAggregateBatchPredicate);
            return this;
        }

//        @Override
//        public LegacyJdbcEventStorageEngine.Builder snapshotFilter(SnapshotFilter snapshotFilter) {
//            super.snapshotFilter(snapshotFilter);
//            return this;
//        }

        @Override
        public LegacyJdbcEventStorageEngine.Builder batchSize(int batchSize) {
            super.batchSize(batchSize);
            return this;
        }

        /**
         * Sets the {@link ConnectionProvider} which provides access to a JDBC connection.
         *
         * @param connectionProvider a {@link ConnectionProvider} which provides access to a JDBC connection
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            assertNonNull(connectionProvider, "ConnectionProvider may not be null");
            this.connectionProvider = connectionProvider;
            return this;
        }

        /**
         * Sets the {@link TransactionManager} used to manage transactions around fetching event data. Required by
         * certain databases for reading blob data.
         *
         * @param transactionManager a {@link TransactionManager} used to manage transactions around fetching event
         *                           data
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder transactionManager(TransactionManager transactionManager) {
            assertNonNull(transactionManager, "TransactionManager may not be null");
            this.transactionManager = transactionManager;
            return this;
        }

        /**
         * Sets the {@code dataType} specifying the serialized type of the Event Message's payload and metadata.
         * Defaults to the {@code byte[]} {@link Class}.
         *
         * @param dataType a {@link Class} specifying the serialized type of the Event Message's payload and metadata
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder dataType(Class<?> dataType) {
            assertNonNull(dataType, "dataType may not be null");
            this.dataType = dataType;
            return this;
        }

        /**
         * Sets the {@link EventSchema} describing the database schema of event entries. Defaults to
         * {@link EventSchema#EventSchema()}.
         *
         * @param schema the {@link EventSchema} describing the database schema of event entries
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder schema(EventSchema schema) {
            assertNonNull(schema, "EventSchema may not be null");
            this.schema = schema;
            return this;
        }

        /**
         * Sets the {@code maxGapOffset} specifying the maximum distance in sequence numbers between a missing event and
         * the event with the highest known index. If the gap is bigger it is assumed that the missing event will not be
         * committed to the store anymore. This event storage engine will no longer look for those events the next time
         * a batch is fetched. Defaults to an integer of {@code 10000}
         * ({@link LegacyJdbcEventStorageEngine#DEFAULT_MAX_GAP_OFFSET}.
         *
         * @param maxGapOffset an {@code int} specifying the maximum distance in sequence numbers between a missing
         *                     event and the event with the highest known index
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder maxGapOffset(int maxGapOffset) {
            assertPositive(maxGapOffset, "maxGapOffset");
            this.maxGapOffset = maxGapOffset;
            return this;
        }

        /**
         * Sets the {@code lowestGlobalSequence} specifying the first expected auto generated sequence number. For most
         * data stores this is 1 unless the table has contained entries before. Defaults to a {@code long} of {@code 1}
         * ({@link LegacyJdbcEventStorageEngine#DEFAULT_LOWEST_GLOBAL_SEQUENCE}).
         *
         * @param lowestGlobalSequence a {@code long} specifying the first expected auto generated sequence number
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder lowestGlobalSequence(long lowestGlobalSequence) {
            assertThat(lowestGlobalSequence,
                       number -> number > 0,
                       "The lowestGlobalSequence must be a positive number");
            this.lowestGlobalSequence = lowestGlobalSequence;
            return this;
        }

        /**
         * Sets the amount of time until a 'gap' in a TrackingToken may be considered timed out. This setting will
         * affect the cleaning process of gaps. Gaps that have timed out will be removed from Tracking Tokens to improve
         * performance of reading events. Defaults to an integer of {@code 60000}
         * ({@link LegacyJdbcEventStorageEngine#DEFAULT_GAP_TIMEOUT}), thus 1 minute.
         *
         * @param gapTimeout an {@code int} specifying the amount of time until a 'gap' in a TrackingToken may be
         *                   considered timed out
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder gapTimeout(int gapTimeout) {
            assertPositive(gapTimeout, "gapTimeout");
            this.gapTimeout = gapTimeout;
            return this;
        }

        /**
         * Sets the threshold of number of gaps in a token before an attempt to clean gaps up is taken. Defaults to an
         * integer of {@code 250} ({@link LegacyJdbcEventStorageEngine#DEFAULT_GAP_CLEANING_THRESHOLD}).
         *
         * @param gapCleaningThreshold an {@code int} specifying the threshold of number of gaps in a token before an
         *                             attempt to clean gaps up is taken
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder gapCleaningThreshold(int gapCleaningThreshold) {
            assertPositive(gapCleaningThreshold, "gapCleaningThreshold");
            this.gapCleaningThreshold = gapCleaningThreshold;
            return this;
        }

        /**
         * Indicates whether an extra query should be performed to verify for gaps in the {@code globalSequence} larger
         * than the configured batch size. These gaps could trick the storage engine into believing there are no more
         * events to read, while there are still positions ahead.
         * <p>
         * This check comes at a cost of an extra query when a batch retrieval yields an empty result. This may increase
         * database pressure when processing are at the HEAD of a stream, as each batch retrieval will result in an
         * extra query, if there are no results.
         * <p>
         * Note that the extra query checks for the smallest globalSequence, higher than the last one seen. This query
         * can be executed using an index, which should be a relatively cheap query for most databases.
         * <p>
         * Defaults to {@code true}
         *
         * @param extendedGapCheckEnabled whether to enable the "extended gap check". Defaults to {@code true}.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder extendedGapCheckEnabled(boolean extendedGapCheckEnabled) {
            this.extendedGapCheckEnabled = extendedGapCheckEnabled;
            return this;
        }

        private void assertPositive(int num, final String numberDescription) {
            assertThat(num, number -> number > 0, "The " + numberDescription + " must be a positive number");
        }

        /**
         * Initializes a {@link LegacyJdbcEventStorageEngine} as specified through this Builder.
         *
         * @return a {@link LegacyJdbcEventStorageEngine} as specified through this Builder
         */
        public LegacyJdbcEventStorageEngine build() {
            return new LegacyJdbcEventStorageEngine(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        @Override
        protected void validate() throws AxonConfigurationException {
            super.validate();
            assertNonNull(connectionProvider, "The ConnectionProvider is a hard requirement and should be provided");
            assertNonNull(transactionManager, "The TransactionManager is a hard requirement and should be provided");
        }
    }
}
