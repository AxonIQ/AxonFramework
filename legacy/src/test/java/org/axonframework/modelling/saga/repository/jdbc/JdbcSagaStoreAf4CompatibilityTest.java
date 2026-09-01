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

import org.axonframework.common.jdbc.DataSourceConnectionProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.repository.Af4CompatibilityTestSuite;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link JdbcSagaStore} satisfies {@link Af4CompatibilityTestSuite} against an HSQLDB table in the Axon
 * Framework 4 layout.
 */
class JdbcSagaStoreAf4CompatibilityTest extends Af4CompatibilityTestSuite {

    private JDBCDataSource dataSource;
    private Connection keepAlive;
    private JdbcSagaStore testSubject;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:af4compat");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        keepAlive = dataSource.getConnection();

        testSubject = JdbcSagaStore.builder()
                                   .connectionProvider(new DataSourceConnectionProvider(dataSource))
                                   .sqlSchema(new HsqlSagaSqlSchema())
                                   .converter(new JacksonConverter())
                                   .build();
        testSubject.createSchema();

        seedAf4Rows();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    @Override
    protected SagaStore<Object> testSubject() {
        return testSubject;
    }

    @Override
    protected void insertAf4Saga(String sagaId, String sagaType, String revision, String serializedSaga) {
        try (PreparedStatement statement = keepAlive.prepareStatement(
                "INSERT INTO SagaEntry (sagaId, revision, sagaType, serializedSaga) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, sagaId);
            statement.setString(2, revision);
            statement.setString(3, sagaType);
            statement.setBytes(4, serializedSaga.getBytes(StandardCharsets.UTF_8));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert saga " + sagaId, e);
        }
    }

    @Override
    protected void insertAf4Association(String sagaId, String sagaType, AssociationValue associationValue) {
        try (PreparedStatement statement = keepAlive.prepareStatement(
                "INSERT INTO AssociationValueEntry (associationKey, associationValue, sagaId, sagaType) "
                        + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, associationValue.getKey());
            statement.setString(2, associationValue.getValue());
            statement.setString(3, sagaId);
            statement.setString(4, sagaType);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert association for " + sagaId, e);
        }
    }

    @Override
    protected String columnOf(String column, String sagaId) {
        try (PreparedStatement statement =
                     keepAlive.prepareStatement("SELECT " + column + " FROM SagaEntry WHERE sagaId = ?")) {
            statement.setString(1, sagaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("expected a SagaEntry row for %s", sagaId).isTrue();
                return resultSet.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read " + column + " for " + sagaId, e);
        }
    }
}
