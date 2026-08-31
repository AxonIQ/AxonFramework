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
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.StubSaga;
import org.axonframework.modelling.saga.repository.jpa.SagaEntry;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link JdbcSagaStore} reads and updates a saga table written by Axon Framework 4.
 * <p>
 * Rows are inserted by hand in the Axon Framework 4 column layout, with the serialized saga spelled out as JSON rather
 * than produced by the store's own converter, so that a change to either the column layout or the stored representation
 * fails here.
 * <p>
 * The {@code revision} column gets particular attention. Axon Framework 4 wrote the saga class's {@code @Revision} value
 * there, and this store neither reads nor writes it, so a value written by Axon Framework 4 has to survive an update
 * untouched.
 */
class JdbcSagaStoreAf4CompatibilityTest {

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");
    private static final AssociationValue ORDER_2 = new AssociationValue("orderId", "order-2");

    private static final String SAGA_WITHOUT_REVISION = "saga-no-revision";
    private static final String SAGA_WITH_REVISION = "saga-with-revision";

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

        // Rows exactly as Axon Framework 4 left them: sagaType holding the saga class name, revision null for a saga
        // without @Revision and set for one with it, and serializedSaga holding the Jackson representation.
        insertAf4Saga(SAGA_WITHOUT_REVISION, null, "{\"handledEvents\":[\"OrderPlaced\"]}");
        insertAf4Saga(SAGA_WITH_REVISION, "2", "{\"handledEvents\":[\"OrderPlaced\",\"OrderPaid\"]}");
        insertAf4Association(SAGA_WITHOUT_REVISION, ORDER_1);
        insertAf4Association(SAGA_WITH_REVISION, ORDER_2);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    @Test
    void readsASagaWrittenByAxonFramework4() {
        // given a row written by Axon Framework 4 / when
        SagaStore.Entry<StubSaga> entry = testSubject.loadSaga(StubSaga.class, SAGA_WITHOUT_REVISION);

        // then
        assertThat(entry).isNotNull();
        assertThat(entry.saga().getHandledEvents()).containsExactly("OrderPlaced");
        assertThat(entry.associationValues()).containsExactly(ORDER_1);
    }

    @Test
    void readsASagaWrittenByAxonFramework4ThatCarriedARevision() {
        // given a row whose revision column is set / when
        SagaStore.Entry<StubSaga> entry = testSubject.loadSaga(StubSaga.class, SAGA_WITH_REVISION);

        // then the revision is irrelevant to reading it back
        assertThat(entry).isNotNull();
        assertThat(entry.saga().getHandledEvents()).containsExactly("OrderPlaced", "OrderPaid");
        assertThat(entry.associationValues()).containsExactly(ORDER_2);
    }

    @Test
    void findsASagaByAnAssociationWrittenByAxonFramework4() {
        // given / when / then
        assertThat(testSubject.findSagas(StubSaga.class, ORDER_1)).containsExactly(SAGA_WITHOUT_REVISION);
        assertThat(testSubject.findSagas(StubSaga.class, ORDER_2)).containsExactly(SAGA_WITH_REVISION);
    }

    @Test
    void updatingASagaLeavesARevisionWrittenByAxonFramework4Untouched() {
        // given a saga whose revision column holds "2"
        assertThat(revisionOf(SAGA_WITH_REVISION)).isEqualTo("2");
        StubSaga updated = new StubSaga();
        updated.handled("OrderShipped");

        // when
        testSubject.updateSaga(StubSaga.class,
                               SAGA_WITH_REVISION,
                               updated,
                               new AssociationValuesImpl(singleton(ORDER_2)));

        // then the state was replaced but the revision survived, so an Axon Framework 4 reader still sees its own value
        SagaStore.Entry<StubSaga> entry = testSubject.loadSaga(StubSaga.class, SAGA_WITH_REVISION);
        assertThat(entry).isNotNull();
        assertThat(entry.saga().getHandledEvents()).containsExactly("OrderShipped");
        assertThat(revisionOf(SAGA_WITH_REVISION)).isEqualTo("2");
    }

    @Test
    void insertingASagaMarksTheRowAsWrittenByThisModule() {
        // given / when
        testSubject.insertSaga(StubSaga.class, "saga-new", new StubSaga(), singleton(ORDER_1));

        // then the row is distinguishable from one Axon Framework 4 left without a revision
        assertThat(revisionOf("saga-new")).isEqualTo(SagaEntry.LEGACY_REVISION);
    }

    @Test
    void updatingASagaInsertedHereKeepsItsMarker() {
        // given a saga inserted by this module
        testSubject.insertSaga(StubSaga.class, "saga-new", new StubSaga(), singleton(ORDER_1));

        // when
        testSubject.updateSaga(StubSaga.class,
                               "saga-new",
                               new StubSaga(),
                               new AssociationValuesImpl(singleton(ORDER_1)));

        // then the update left the column alone, so the marker is still there
        assertThat(revisionOf("saga-new")).isEqualTo(SagaEntry.LEGACY_REVISION);
    }

    @Test
    void theSagaTypeColumnHoldsTheSagaClassName() {
        // given / when
        testSubject.insertSaga(StubSaga.class, "saga-new", new StubSaga(), singleton(ORDER_1));

        // then what Axon Framework 4 wrote through the serializer is what is written here
        assertThat(sagaTypeOf("saga-new")).isEqualTo(StubSaga.class.getName());
    }

    private void insertAf4Saga(String sagaId, String revision, String serializedSaga) throws SQLException {
        try (PreparedStatement statement = keepAlive.prepareStatement(
                "INSERT INTO SagaEntry (sagaId, revision, sagaType, serializedSaga) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, sagaId);
            statement.setString(2, revision);
            statement.setString(3, StubSaga.class.getName());
            statement.setBytes(4, serializedSaga.getBytes(StandardCharsets.UTF_8));
            statement.executeUpdate();
        }
    }

    private void insertAf4Association(String sagaId, AssociationValue associationValue) throws SQLException {
        try (PreparedStatement statement = keepAlive.prepareStatement(
                "INSERT INTO AssociationValueEntry (associationKey, associationValue, sagaId, sagaType) "
                        + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, associationValue.getKey());
            statement.setString(2, associationValue.getValue());
            statement.setString(3, sagaId);
            statement.setString(4, StubSaga.class.getName());
            statement.executeUpdate();
        }
    }

    private String revisionOf(String sagaId) {
        return columnOf("revision", sagaId);
    }

    private String sagaTypeOf(String sagaId) {
        return columnOf("sagaType", sagaId);
    }

    private String columnOf(String column, String sagaId) {
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
