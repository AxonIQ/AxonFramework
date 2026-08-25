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

package org.axonframework.modelling.saga.repository.jdbc;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.DataSourceConnectionProvider;
import org.axonframework.common.jdbc.UnitOfWorkAwareConnectionProviderWrapper;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.SagaStorageException;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.jpa.SagaEntry;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.sql.DataSource;

import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.jdbc.JdbcUtils.closeQuietly;

/**
 * A {@link SagaStore} implementation that uses JDBC to store and find Saga instances.
 * <p>
 * Before using this store make sure the database contains an association value- and saga entry table. These can be
 * constructed with {@link SagaSqlSchema#sql_createTableAssocValueEntry(Connection)} and {@link
 * SagaSqlSchema#sql_createTableSagaEntry(Connection)} respectively. For convenience, these tables can be constructed
 * through the {@link JdbcSagaStore#createSchema()} operation.
 *
 * @author Allard Buijze
 * @author Kristian Rosenvold
 * @since 2.1
 */
public class JdbcSagaStore implements SagaStore<Object> {

    private static final Logger logger = LoggerFactory.getLogger(JdbcSagaStore.class);

    private final ConnectionProvider connectionProvider;
    private final SagaSqlSchema sqlSchema;
    private Serializer serializer;

    /**
     * Instantiate a {@link JdbcSagaStore} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link ConnectionProvider}, {@link SagaSqlSchema} and {@link Serializer} are not
     * {@code null}, and will throw an {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link JdbcSagaStore} instance
     */
    protected JdbcSagaStore(Builder builder) {
        builder.validate();
        this.connectionProvider = builder.connectionProvider;
        this.sqlSchema = builder.sqlSchema;
        this.serializer = builder.serializer.get();
    }

    /**
     * Instantiate a Builder to be able to create a {@link JdbcSagaStore}.
     * <p>
     * The {@link SagaSqlSchema} is defaulted to an {@link GenericSagaSqlSchema}.
     * <p>
     * The {@link ConnectionProvider} and {@link Serializer} are <b>hard requirements</b> and as such should be
     * provided.
     * <p>
     * You can choose to provide a {@link DataSource} instead of a ConnectionProvider, but in that case the used
     * ConnectionProvider will be a {@link DataSourceConnectionProvider} wrapped by a {@link
     * UnitOfWorkAwareConnectionProviderWrapper}.
     *
     * @return a Builder to be able to create a {@link JdbcSagaStore}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <S> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier) {
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        Connection conn = null;
        try {
            conn = connectionProvider.getConnection();
            statement = sqlSchema.sql_loadSaga(conn, sagaIdentifier);
            resultSet = statement.executeQuery();

            SerializedObject<?> serializedSaga = null;
            if (resultSet.next()) {
                serializedSaga = sqlSchema.readSerializedSaga(resultSet);
            }
            if (serializedSaga == null) {
                return null;
            }
            S loadedSaga = serializer.deserialize(serializedSaga);
            if (logger.isDebugEnabled()) {
                logger.debug("Loaded saga id [{}] of type [{}]", sagaIdentifier, loadedSaga.getClass().getName());
            }

            return new EntryImpl<>(loadAssociations(conn, sagaTypeName(sagaType), sagaIdentifier), loadedSaga);
        } catch (SQLException e) {
            throw new SagaStorageException("Exception while loading a Saga", e);
        } finally {
            closeQuietly(statement);
            closeQuietly(resultSet);
            closeQuietly(conn);
        }
    }

    @Override
    public Set<String> findSagas(Class<?> sagaType, AssociationValue associationValue) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection conn = null;
        try {
            conn = connectionProvider.getConnection();
            statement = sqlSchema.sql_findAssocSagaIdentifiers(
                    conn, associationValue.getKey(), associationValue.getValue(), sagaTypeName(sagaType)
            );
            resultSet = statement.executeQuery();
            Set<String> result = new TreeSet<>();
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
            return result;
        } catch (SQLException e) {
            throw new SagaStorageException("Exception while reading saga associations", e);
        } finally {
            closeQuietly(statement);
            closeQuietly(resultSet);
            closeQuietly(conn);
        }
    }

    @Override
    public void deleteSaga(Class<?> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues) {
        PreparedStatement statement1 = null;
        PreparedStatement statement2 = null;
        Connection conn = null;
        try {
            conn = connectionProvider.getConnection();
            statement1 = sqlSchema.sql_deleteAssociationEntries(conn, sagaIdentifier);
            statement2 = sqlSchema.sql_deleteSagaEntry(conn, sagaIdentifier);
            statement1.executeUpdate();
            statement2.executeUpdate();
        } catch (SQLException e) {
            throw new SagaStorageException("Exception occurred while attempting to delete a saga entry", e);
        } finally {
            closeQuietly(statement1);
            closeQuietly(statement2);
            closeQuietly(conn);
        }
    }

    @Override
    public void updateSaga(Class<?> sagaType, String sagaIdentifier, Object saga, AssociationValues associationValues) {
        SagaEntry<?> entry = new SagaEntry<>(saga, sagaIdentifier, serializer);
        if (logger.isDebugEnabled()) {
            logger.debug("Updating saga id {} as {}", sagaIdentifier, new String(entry.getSerializedSaga(),
                                                                                 Charset.forName("UTF-8")));
        }

        int updateCount;
        PreparedStatement statement = null;
        Connection conn = null;
        try {
            conn = connectionProvider.getConnection();
            statement = sqlSchema.sql_updateSaga(conn,
                                                 entry.getSagaId(),
                                                 entry.getSerializedSaga(),
                                                 entry.getSagaType(),
                                                 entry.getRevision()
            );
            updateCount = statement.executeUpdate();
            if (updateCount != 0) {
                for (AssociationValue associationValue : associationValues.addedAssociations()) {
                    closeQuietly(statement);
                    statement = sqlSchema.sql_storeAssocValue(conn,
                                                              associationValue.getKey(),
                                                              associationValue.getValue(),
                                                              sagaTypeName(sagaType),
                                                              sagaIdentifier);
                    statement.executeUpdate();
                }
                for (AssociationValue associationValue : associationValues.removedAssociations()) {
                    closeQuietly(statement);
                    statement = sqlSchema.sql_removeAssocValue(conn,
                                                               associationValue.getKey(),
                                                               associationValue.getValue(),
                                                               sagaTypeName(sagaType),
                                                               sagaIdentifier);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new SagaStorageException("Exception occurred while attempting to update a saga", e);
        } finally {
            closeQuietly(statement);
            closeQuietly(conn);
        }

        if (updateCount == 0) {
            logger.warn("Expected to be able to update a Saga instance, but no rows were found.");
        }
    }

    @Override
    public void insertSaga(Class<?> sagaType, String sagaIdentifier, Object saga,
                           Set<AssociationValue> associationValues) {
        SagaEntry<?> entry = new SagaEntry<>(saga, sagaIdentifier, serializer);
        if (logger.isDebugEnabled()) {
            logger.debug("Storing saga id {} as {}", sagaIdentifier, new String(entry.getSerializedSaga(),
                                                                                Charset.forName("UTF-8")));
        }
        Connection conn = null;
        PreparedStatement statement = null;
        try {
            conn = connectionProvider.getConnection();
            statement = sqlSchema.sql_storeSaga(conn, entry.getSagaId(), entry.getRevision(), entry.getSagaType(),
                                                entry.getSerializedSaga());
            statement.executeUpdate();

            for (AssociationValue associationValue : associationValues) {
                closeQuietly(statement);
                statement = sqlSchema.sql_storeAssocValue(conn,
                                                          associationValue.getKey(),
                                                          associationValue.getValue(),
                                                          sagaTypeName(sagaType),
                                                          sagaIdentifier);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new SagaStorageException("Exception occurred while attempting to store a Saga Entry", e);
        } finally {
            closeQuietly(statement);
            closeQuietly(conn);
        }
    }

    private Set<AssociationValue> loadAssociations(final Connection conn, final String sagaTypeName, final String sagaIdentifier) throws SQLException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = sqlSchema.sql_findAssociations(conn, sagaIdentifier, sagaTypeName);
            resultSet = statement.executeQuery();
            return sqlSchema.readAssociationValues(resultSet);
        } finally {
            closeQuietly(statement);
            closeQuietly(resultSet);
        }
    }


    private String sagaTypeName(Class<?> sagaType) {
        return serializer.typeForClass(sagaType).getName();
    }

    /**
     * Sets the Serializer instance to serialize Sagas with. Defaults to the XStream Serializer.
     *
     * @param serializer the Serializer instance to serialize Sagas with
     */
    public void setSerializer(Serializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Creates the SQL Schema required to store Sagas and their associations.
     *
     * @throws SQLException when an error occurs preparing of executing the required statements
     */
    public void createSchema() throws SQLException {
        final Connection connection = connectionProvider.getConnection();
        try {
            sqlSchema.sql_createTableSagaEntry(connection).executeUpdate();
            sqlSchema.sql_createTableAssocValueEntry(connection).executeUpdate();
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Builder class to instantiate a {@link JdbcSagaStore}.
     * <p>
     * The {@link SagaSqlSchema} is defaulted to an {@link GenericSagaSqlSchema}.
     * <p>
     * The {@link ConnectionProvider} and {@link Serializer} are <b>hard requirements</b> and as such should be
     * provided.
     * <p>
     * You can choose to provide a {@link DataSource} instead of a ConnectionProvider, but in that case the used
     * ConnectionProvider will be a {@link DataSourceConnectionProvider} wrapped by a {@link
     * UnitOfWorkAwareConnectionProviderWrapper}.
     */
    public static class Builder {

        private ConnectionProvider connectionProvider;
        private SagaSqlSchema sqlSchema = new GenericSagaSqlSchema();
        private Supplier<Serializer> serializer;

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
         * Sets the {@link ConnectionProvider} by providing a {@link DataSource}. The given {@code dataSource} in turn
         * will added to a {@link DataSourceConnectionProvider}, which is wrapped by a
         * {@link UnitOfWorkAwareConnectionProviderWrapper}. This will provide access to a JDBC connection for this
         * {@link SagaStore} implementation.
         *
         * @param dataSource a {@link DataSource} which ends up in a {@link DataSourceConnectionProvider}, wrapped by a
         *                   {@link UnitOfWorkAwareConnectionProviderWrapper} as the {@link ConnectionProvider} for this
         *                   {@link SagaStore} implementation
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder dataSource(DataSource dataSource) {
            assertNonNull(dataSource, "DataSource used to instantiate a ConnectionProvider may not be null");
            DataSourceConnectionProvider dataSourceConnectionProvider = new DataSourceConnectionProvider(dataSource);
            this.connectionProvider = new UnitOfWorkAwareConnectionProviderWrapper(dataSourceConnectionProvider);
            return this;
        }

        /**
         * Sets the {@link SagaSqlSchema} defining the SQL operations to execute for this {@link SagaStore}
         * implementation. Defaults to a {@link GenericSagaSqlSchema}.
         *
         * @param sqlSchema the {@link SagaSqlSchema} defining the SQL operations to execute for this {@link SagaStore}
         *                  implementation
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder sqlSchema(SagaSqlSchema sqlSchema) {
            assertNonNull(sqlSchema, "SagaSqlSchema may not be null");
            this.sqlSchema = sqlSchema;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to de-/serialize a Saga instance.
         *
         * @param serializer a {@link Serializer} used to de-/serialize a Saga instance
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder serializer(Serializer serializer) {
            assertNonNull(serializer, "Serializer may not be null");
            this.serializer = () -> serializer;
            return this;
        }

        /**
         * Initializes a {@link JdbcSagaStore} as specified through this Builder.
         *
         * @return a {@link JdbcSagaStore} as specified through this Builder
         */
        public JdbcSagaStore build() {
            return new JdbcSagaStore(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(connectionProvider, "The ConnectionProvider is a hard requirement and should be provided");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided");
        }
    }

    private static class EntryImpl<S> implements Entry<S> {

        private final Set<AssociationValue> associations;
        private final S loadedSaga;

        public EntryImpl(Set<AssociationValue> associations, S loadedSaga) {
            this.associations = associations;
            this.loadedSaga = loadedSaga;
        }

        @Override
        public Set<AssociationValue> associationValues() {
            return associations;
        }

        @Override
        public S saga() {
            return loadedSaga;
        }
    }
}
