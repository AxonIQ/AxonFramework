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

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating that {@link HsqlSagaSqlSchema} honours the {@link SagaSchema} it is given, which is what allows
 * the tables to be named something other than the default.
 *
 * @author Mateusz Nowak
 */
class HsqlSagaSqlSchemaTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        JDBCDataSource dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:hsqlschema");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        connection = dataSource.getConnection();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        connection.close();
    }

    @Test
    void theDefaultConstructorCreatesTheDefaultlyNamedTables() throws SQLException {
        // given
        HsqlSagaSqlSchema testSubject = new HsqlSagaSqlSchema();

        // when
        createTables(testSubject);

        // then
        assertThat(rowCountOf("SagaEntry")).isZero();
        assertThat(rowCountOf("AssociationValueEntry")).isZero();
    }

    @Test
    void aGivenSagaSchemaNamesTheTablesAndColumns() throws SQLException {
        // given a schema naming everything differently from the defaults
        SagaSchema sagaSchema = SagaSchema.builder()
                                          .sagaEntryTable("CustomSagaEntry")
                                          .associationValueEntryTable("CustomAssociationValueEntry")
                                          .associationKeyColumn("customAssociationKey")
                                          .build();
        HsqlSagaSqlSchema testSubject = new HsqlSagaSqlSchema(sagaSchema);

        // when
        createTables(testSubject);

        // then the custom names exist and the defaults were not created
        assertThat(rowCountOf("CustomSagaEntry")).isZero();
        assertThat(rowCountOf("CustomAssociationValueEntry")).isZero();
        assertThat(columnsOf("CustomAssociationValueEntry")).contains("CUSTOMASSOCIATIONKEY");
        assertThat(tableExists("SagaEntry")).isFalse();
    }

    private void createTables(HsqlSagaSqlSchema schema) throws SQLException {
        try (PreparedStatement statement = schema.sql_createTableSagaEntry(connection)) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = schema.sql_createTableAssocValueEntry(connection)) {
            statement.executeUpdate();
        }
    }

    private int rowCountOf(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (ResultSet resultSet =
                     connection.getMetaData().getTables(null, null, table.toUpperCase(), new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private String columnsOf(String table) throws SQLException {
        StringBuilder columns = new StringBuilder();
        try (ResultSet resultSet = connection.getMetaData().getColumns(null, null, table.toUpperCase(), null)) {
            while (resultSet.next()) {
                columns.append(resultSet.getString("COLUMN_NAME")).append(' ');
            }
        }
        return columns.toString();
    }
}
