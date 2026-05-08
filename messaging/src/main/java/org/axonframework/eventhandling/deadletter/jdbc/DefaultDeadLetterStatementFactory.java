/*
 * Copyright (c) 2010-2024. Axon Framework
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

package org.axonframework.eventhandling.deadletter.jdbc;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.DateTimeUtils;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.eventhandling.DomainEventMessage;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.messaging.MetaData;
import org.axonframework.messaging.deadletter.Cause;
import org.axonframework.messaging.deadletter.DeadLetter;
import org.axonframework.serialization.SerializedObject;
import org.axonframework.serialization.Serializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Default implementation of the {@link DeadLetterStatementFactory} used by the {@link JdbcSequencedDeadLetterQueue}
 * Constructs {@link PreparedStatement PreparedStatements} that are compatible with most databases.
 * <p>
 * This factory expects a {@link DeadLetterSchema} to base the table and columns names used for <b>all</b>
 * {@code PreparedStatements}. Furthermore, it uses the configurable {@code genericSerializer} to serialize
 * {@link TrackingToken TrackingTokens} in {@link TrackedEventMessage} instances. Lastly, this factory uses the
 * {@code eventSerializer} to serialize the {@link EventMessage#getPayload() event payload},
 * {@link EventMessage#getMetaData() MetaData}, and {@link DeadLetter#diagnostics() diagnostics} of any
 * {@code DeadLetter}.
 * <p>
 * This factory and the {@link DeadLetterJdbcConverter} must use the same {@link Serializer Serializers} and
 * {@code DeadLetterSchema} for the applicable fields.
 *
 * @param <E> An implementation of {@link EventMessage} within the {@link DeadLetter} this factory constructs
 *            {@link PreparedStatement PreparedStatements} for.
 * @author Steven van Beelen
 * @since 4.8.0
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection"})
public class DefaultDeadLetterStatementFactory<E extends EventMessage<?>> implements DeadLetterStatementFactory<E> {

    private final DeadLetterSchema schema;
    private final Serializer genericSerializer;
    private final Serializer eventSerializer;

    /**
     * Instantiate a default {@link DeadLetterStatementFactory} based on the given {@code builder}.
     * <p>
     * Will validate whether the {@link Builder#genericSerializer(Serializer) generic Serializer} and
     * {@link Builder#eventSerializer(Serializer) event Serializer} are set. If for either this is not the case an
     * {@link AxonConfigurationException} is thrown.
     *
     * @param builder The {@link Builder} used to instantiate a {@link DefaultDeadLetterStatementFactory} instance.
     */
    protected DefaultDeadLetterStatementFactory(Builder<E> builder) {
        builder.validate();
        this.schema = builder.schema;
        this.genericSerializer = builder.genericSerializer;
        this.eventSerializer = builder.eventSerializer;
    }

    /**
     * Instantiate a builder to construct a {@link DefaultDeadLetterStatementFactory}.
     * <p>
     * The {@link Builder#schema(DeadLetterSchema) schema} is defaulted to a {@link DeadLetterSchema#defaultSchema()}.
     * The {@link Builder#genericSerializer(Serializer) generic Serializer} and
     * {@link Builder#eventSerializer(Serializer) event Serializer} are hard requirements and should be provided.
     *
     * @param <E> An implementation of {@link EventMessage} within the {@link DeadLetter} this factory constructs
     *            {@link PreparedStatement PreparedStatements} for.
     * @return A builder that con construct a {@link DefaultDeadLetterStatementFactory}.
     */
    public static <E extends EventMessage<?>> Builder<E> builder() {
        return new Builder<>();
    }

    @Override
    public PreparedStatement enqueueStatement(@Nonnull Connection connection,
                                              @Nonnull String processingGroup,
                                              @Nonnull String sequenceIdentifier,
                                              @Nonnull DeadLetter<? extends E> letter,
                                              long sequenceIndex) throws SQLException {
        String sql = "INSERT INTO " + schema.deadLetterTable() + " "
                + "(" + schema.deadLetterFields() + ") "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        PreparedStatement statement = connection.prepareStatement(sql);
        AtomicInteger fieldIndex = new AtomicInteger(1);
        E eventMessage = letter.message();

        setIdFields(statement, fieldIndex, processingGroup, sequenceIdentifier, sequenceIndex);
        setEventFields(statement, fieldIndex, eventMessage);
        setDomainEventFields(statement, fieldIndex, eventMessage);
        setTrackedEventFields(statement, fieldIndex, eventMessage);
        setDeadLetterFields(statement, fieldIndex, letter);

        return statement;
    }

    private void setIdFields(PreparedStatement statement,
                             AtomicInteger fieldIndex,
                             String processingGroup,
                             String sequenceIdentifier,
                             long sequenceIndex) throws SQLException {
        String deadLetterId = IdentifierFactory.getInstance().generateIdentifier();
        statement.setString(fieldIndex.getAndIncrement(), deadLetterId);
        statement.setString(fieldIndex.getAndIncrement(), processingGroup);
        statement.setString(fieldIndex.getAndIncrement(), sequenceIdentifier);
        statement.setLong(fieldIndex.getAndIncrement(), sequenceIndex);
    }

    private void setEventFields(PreparedStatement statement,
                                AtomicInteger fieldIndex,
                                E eventMessage) throws SQLException {
        SerializedObject<byte[]> serializedPayload = eventMessage.serializePayload(eventSerializer, byte[].class);
        SerializedObject<byte[]> serializedMetaData = eventMessage.serializeMetaData(eventSerializer, byte[].class);
        statement.setString(fieldIndex.getAndIncrement(), eventMessage.getClass().getName());
        statement.setString(fieldIndex.getAndIncrement(), eventMessage.getIdentifier());
        statement.setString(fieldIndex.getAndIncrement(), DateTimeUtils.formatInstant(eventMessage.getTimestamp()));
        statement.setString(fieldIndex.getAndIncrement(), serializedPayload.getType().getName());
        statement.setString(fieldIndex.getAndIncrement(), serializedPayload.getType().getRevision());
        statement.setBytes(fieldIndex.getAndIncrement(), serializedPayload.getData());
        statement.setBytes(fieldIndex.getAndIncrement(), serializedMetaData.getData());
    }

    private void setDomainEventFields(PreparedStatement statement,
                                      AtomicInteger fieldIndex,
                                      EventMessage<?> eventMessage) throws SQLException {
        boolean isDomainEvent = eventMessage instanceof DomainEventMessage;
        setDomainEventFields(statement, fieldIndex, isDomainEvent ? (DomainEventMessage<?>) eventMessage : null);
    }

    private void setDomainEventFields(PreparedStatement statement,
                                      AtomicInteger fieldIndex,
                                      DomainEventMessage<?> eventMessage) throws SQLException {
        statement.setString(fieldIndex.getAndIncrement(),
                            getOrDefault(eventMessage, DomainEventMessage::getType, null));
        statement.setString(fieldIndex.getAndIncrement(),
                            getOrDefault(eventMessage, DomainEventMessage::getAggregateIdentifier, null));
        statement.setLong(fieldIndex.getAndIncrement(),
                          getOrDefault(eventMessage, DomainEventMessage::getSequenceNumber, -1L));
    }

    private void setTrackedEventFields(PreparedStatement statement,
                                       AtomicInteger fieldIndex,
                                       EventMessage<?> eventMessage) throws SQLException {
        boolean isTrackedEvent = eventMessage instanceof TrackedEventMessage;
        setTrackedEventFields(statement,
                              fieldIndex,
                              isTrackedEvent ? ((TrackedEventMessage<?>) eventMessage).trackingToken() : null);
    }

    private void setTrackedEventFields(PreparedStatement statement,
                                       AtomicInteger fieldIndex,
                                       TrackingToken token) throws SQLException {
        if (token != null) {
            SerializedObject<byte[]> serializedToken = genericSerializer.serialize(token, byte[].class);
            statement.setString(fieldIndex.getAndIncrement(), serializedToken.getType().getName());
            statement.setBytes(fieldIndex.getAndIncrement(), serializedToken.getData());
        } else {
            statement.setString(fieldIndex.getAndIncrement(), null);
            statement.setBytes(fieldIndex.getAndIncrement(), null);
        }
    }

    private void setDeadLetterFields(PreparedStatement statement,
                                     AtomicInteger fieldIndex,
                                     DeadLetter<? extends E> letter) throws SQLException {
        statement.setString(fieldIndex.getAndIncrement(), DateTimeUtils.formatInstant(letter.enqueuedAt()));
        statement.setString(fieldIndex.getAndIncrement(), DateTimeUtils.formatInstant(letter.lastTouched()));
        Optional<Cause> cause = letter.cause();
        statement.setString(fieldIndex.getAndIncrement(), cause.map(Cause::type).orElse(null));
        statement.setString(fieldIndex.getAndIncrement(), cause.map(Cause::message).orElse(null));
        SerializedObject<byte[]> serializedDiagnostics = eventSerializer.serialize(letter.diagnostics(), byte[].class);
        statement.setBytes(fieldIndex.getAndIncrement(), serializedDiagnostics.getData());
    }

    @Override
    public PreparedStatement maxIndexStatement(@Nonnull Connection connection,
                                               @Nonnull String processingGroup,
                                               @Nonnull String sequenceId) throws SQLException {
        String sql = "SELECT MAX(" + schema.sequenceIndexColumn() + ") "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=? "
                + "AND " + schema.sequenceIdentifierColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        statement.setString(2, sequenceId);
        return statement;
    }

    @Override
    public PreparedStatement evictStatement(@Nonnull Connection connection,
                                            @Nonnull String identifier) throws SQLException {
        String sql = "DELETE "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.deadLetterIdentifierColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, identifier);
        return statement;
    }

    @Override
    public PreparedStatement requeueStatement(@Nonnull Connection connection,
                                              @Nonnull String letterIdentifier,
                                              Cause cause,
                                              @Nonnull Instant lastTouched,
                                              MetaData diagnostics) throws SQLException {
        String sql = "UPDATE " + schema.deadLetterTable() + " SET "
                + schema.causeTypeColumn() + "=?, "
                + schema.causeMessageColumn() + "=?, "
                + schema.lastTouchedColumn() + "=?, "
                + schema.diagnosticsColumn() + "=?, "
                + schema.processingStartedColumn() + "=NULL "
                + "WHERE " + schema.deadLetterIdentifierColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, getOrDefault(cause, Cause::type, null));
        statement.setString(2, getOrDefault(cause, Cause::message, null));
        statement.setString(3, DateTimeUtils.formatInstant(lastTouched));
        SerializedObject<byte[]> serializedDiagnostics = eventSerializer.serialize(diagnostics, byte[].class);
        statement.setBytes(4, serializedDiagnostics.getData());
        statement.setString(5, letterIdentifier);
        return statement;
    }

    @Override
    public PreparedStatement containsStatement(@Nonnull Connection connection,
                                               @Nonnull String processingGroup,
                                               @Nonnull String sequenceId) throws SQLException {
        return sequenceSizeStatement(connection, processingGroup, sequenceId);
    }

    @Override
    public PreparedStatement letterSequenceStatement(@Nonnull Connection connection,
                                                     @Nonnull String processingGroup,
                                                     @Nonnull String sequenceId,
                                                     int offset,
                                                     int maxSize) throws SQLException {
        String sql = "SELECT * "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=? "
                + "AND " + schema.sequenceIdentifierColumn() + "=? "
                + "AND " + schema.sequenceIndexColumn() + ">=? "
                + "ORDER BY " + schema.sequenceIndexColumn() + " "
                + "LIMIT ?";

        PreparedStatement statement =
                connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

        statement.setString(1, processingGroup);
        statement.setString(2, sequenceId);
        statement.setInt(3, offset);
        statement.setInt(4, maxSize);
        return statement;
    }

    @Override
    public PreparedStatement sequenceIdentifiersStatement(@Nonnull Connection connection,
                                                          @Nonnull String processingGroup) throws SQLException {
        String sql = "SELECT dl." + schema.sequenceIdentifierColumn() + " "
                + "FROM " + schema.deadLetterTable() + " dl "
                + "WHERE dl." + schema.processingGroupColumn() + "=? "
                + "AND dl." + schema.sequenceIndexColumn() + "=("
                + "SELECT MIN(dl2." + schema.sequenceIndexColumn() + ") "
                + "FROM " + schema.deadLetterTable() + " dl2 "
                + "WHERE dl2." + schema.processingGroupColumn() + "=dl." + schema.processingGroupColumn() + " "
                + "AND dl2." + schema.sequenceIdentifierColumn() + "=dl." + schema.sequenceIdentifierColumn() + ") "
                + "ORDER BY dl." + schema.lastTouchedColumn() + " "
                + "ASC";

        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        return statement;
    }

    @Override
    public PreparedStatement sizeStatement(@Nonnull Connection connection,
                                           @Nonnull String processingGroup) throws SQLException {
        String sql = "SELECT COUNT(*) "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        return statement;
    }

    @Override
    public PreparedStatement sequenceSizeStatement(@Nonnull Connection connection,
                                                   @Nonnull String processingGroup,
                                                   @Nonnull String sequenceId) throws SQLException {
        String sql = "SELECT COUNT(*) "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=? "
                + "AND " + schema.sequenceIdentifierColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        statement.setString(2, sequenceId);
        return statement;
    }

    @Override
    public PreparedStatement amountOfSequencesStatement(@Nonnull Connection connection,
                                                        @Nonnull String processingGroup) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT " + schema.sequenceIdentifierColumn() + ") "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        return statement;
    }

    @Override
    public PreparedStatement claimableSequencesStatement(@Nonnull Connection connection,
                                                         @Nonnull String processingGroup,
                                                         @Nonnull Instant processingStartedLimit,
                                                         int offset,
                                                         int maxSize) throws SQLException {
        String sql = "SELECT * "
                + "FROM " + schema.deadLetterTable() + " dl "
                + "WHERE dl." + schema.processingGroupColumn() + "=? "
                + "AND dl." + schema.sequenceIndexColumn() + "="
                + "("
                + "SELECT MIN(dl2." + schema.sequenceIndexColumn() + ") "
                + "FROM " + schema.deadLetterTable() + " dl2 "
                + "WHERE dl2." + schema.processingGroupColumn() + "=dl." + schema.processingGroupColumn() + " "
                + "AND dl2." + schema.sequenceIdentifierColumn() + "=dl." + schema.sequenceIdentifierColumn()
                + ") "
                + "AND ("
                + "dl." + schema.processingStartedColumn() + " IS NULL "
                + "OR dl." + schema.processingStartedColumn() + "<?"
                + ") "
                + "ORDER BY dl." + schema.lastTouchedColumn() + " "
                + "ASC "
                + "LIMIT ? "
                + "OFFSET ?";

        PreparedStatement statement =
                connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

        statement.setString(1, processingGroup);
        statement.setString(2, DateTimeUtils.formatInstant(processingStartedLimit));
        statement.setInt(3, maxSize);
        statement.setInt(4, offset);
        return statement;
    }

    @Override
    public PreparedStatement claimStatement(@Nonnull Connection connection,
                                            @Nonnull String identifier,
                                            @Nonnull Instant current,
                                            @Nonnull Instant processingStartedLimit) throws SQLException {
        String sql = "UPDATE " + schema.deadLetterTable() + " SET "
                + schema.processingStartedColumn() + "=? "
                + "WHERE " + schema.deadLetterIdentifierColumn() + "=? "
                + "AND ("
                + schema.processingStartedColumn() + " IS NULL "
                + "OR " + schema.processingStartedColumn() + "<?"
                + ")";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, DateTimeUtils.formatInstant(current));
        statement.setString(2, identifier);
        statement.setString(3, DateTimeUtils.formatInstant(processingStartedLimit));
        return statement;
    }

    @Override
    public PreparedStatement nextLetterInSequenceStatement(@Nonnull Connection connection,
                                                           @Nonnull String processingGroup,
                                                           @Nonnull String sequenceIdentifier,
                                                           long sequenceIndex) throws SQLException {
        String sql = "SELECT * "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=? "
                + "AND " + schema.sequenceIdentifierColumn() + "=? "
                + "AND " + schema.sequenceIndexColumn() + ">? "
                + "ORDER BY " + schema.sequenceIndexColumn() + " "
                + "ASC "
                + "LIMIT 1";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        statement.setString(2, sequenceIdentifier);
        statement.setLong(3, sequenceIndex);
        return statement;
    }

    @Override
    public PreparedStatement clearStatement(@Nonnull Connection connection,
                                            @Nonnull String processingGroup) throws SQLException {
        String sql = "DELETE "
                + "FROM " + schema.deadLetterTable() + " "
                + "WHERE " + schema.processingGroupColumn() + "=?";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, processingGroup);
        return statement;
    }

    /**
     * Builder class to instantiate a {@link DefaultDeadLetterStatementFactory}.
     * <p>
     * The {@link Builder#schema(DeadLetterSchema) schema} is defaulted to a {@link DeadLetterSchema#defaultSchema()}.
     * The {@link Builder#genericSerializer(Serializer) generic Serializer} and
     * {@link Builder#eventSerializer(Serializer) event Serializer} are hard requirements and should be provided.
     *
     * @param <E> An implementation of {@link EventMessage} within the {@link DeadLetter} this factory constructs
     *            {@link PreparedStatement PreparedStatements} for.
     */
    protected static class Builder<E extends EventMessage<?>> {

        private DeadLetterSchema schema = DeadLetterSchema.defaultSchema();
        private Serializer genericSerializer;
        private Serializer eventSerializer;

        /**
         * Sets the given {@code schema} used to define the table and column names used when constructing <b>all</b>
         * {@link PreparedStatement PreparedStatements}. Defaults to a {@link DeadLetterSchema#defaultSchema()}.
         *
         * @param schema The {@link DeadLetterSchema} used to define the table and column names used when constructing
         *               <b>all</b> {@link PreparedStatement PreparedStatements}.
         * @return The current Builder, for fluent interfacing.
         */
        public Builder<E> schema(DeadLetterSchema schema) {
            assertNonNull(schema, "DeadLetterSchema may not be null");
            this.schema = schema;
            return this;
        }


        /**
         * Sets the {@link Serializer} to serialize the {@link TrackingToken} of a {@link TrackedEventMessage} instance
         * in the {@link DeadLetter} when storing it to the database.
         *
         * @param genericSerializer The serializer to use for {@link TrackingToken TrackingTokens} in a
         *                          {@link TrackedEventMessage}.
         * @return The current Builder, for fluent interfacing.
         */
        public Builder<E> genericSerializer(Serializer genericSerializer) {

            assertNonNull(genericSerializer, "The generic serializer may not be null");
            this.genericSerializer = genericSerializer;
            return this;
        }

        /**
         * Sets the {@link Serializer} to serialize the {@link EventMessage#getPayload() event payload},
         * {@link EventMessage#getMetaData() MetaData}, and {@link DeadLetter#diagnostics() diagnostics} of the
         * {@link DeadLetter} when storing it to a database.
         *
         * @param eventSerializer The serializer to use for {@link EventMessage#getPayload() event payload}s,
         *                        {@link EventMessage#getMetaData() MetaData} instances, and
         *                        {@link DeadLetter#diagnostics() diagnostics}.
         * @return The current Builder, for fluent interfacing.
         */
        public Builder<E> eventSerializer(Serializer eventSerializer) {
            assertNonNull(eventSerializer, "The event serializer may not be null");
            this.eventSerializer = eventSerializer;
            return this;
        }

        /**
         * Initializes a {@link DefaultDeadLetterStatementFactory} as specified through this Builder.
         *
         * @return A {@link DefaultDeadLetterStatementFactory} as specified through this Builder.
         */
        public DefaultDeadLetterStatementFactory<E> build() {
            return new DefaultDeadLetterStatementFactory<>(this);
        }

        /**
         * Validate whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException When one field asserts to be incorrect according to the Builder's
         *                                    specifications.
         */
        protected void validate() {
            assertNonNull(genericSerializer, "The generic Serializer is a hard requirement and should be provided");
            assertNonNull(eventSerializer, "The event Serializer is a hard requirement and should be provided");
        }
    }
}
