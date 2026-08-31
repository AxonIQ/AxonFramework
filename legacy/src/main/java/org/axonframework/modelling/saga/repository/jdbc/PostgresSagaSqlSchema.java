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

import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * SQL schema supporting PostgreSQL databases.
 * <p/>
 * This implementation uses the appropriate PostgreSQL data types (serial, bytea).
 *
 * @author Jochen Munz
 * @since 2.4
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection"})
public class PostgresSagaSqlSchema extends GenericSagaSqlSchema {

    private boolean exclusiveLoad = false;

    /**
     * Initialize a PostgresSagaSqlSchema using the default schema configuration.
     */
    public PostgresSagaSqlSchema() {
    }

    /**
     * Initialize a PostgresSagaSqlSchema using the given {@code sagaSchema}.
     *
     * @param sagaSchema the saga schema configuration
     */
    public PostgresSagaSqlSchema(SagaSchema sagaSchema) {
        super(sagaSchema);
    }

    @Override
    public PreparedStatement sql_createTableAssocValueEntry(Connection conn) throws SQLException {
        final String sql = "CREATE TABLE IF NOT EXISTS " + sagaSchema().associationValueEntryTable() + " (\n" +
                "        id bigserial NOT NULL,\n" +
                "        " + sagaSchema.associationKeyColumn() + " VARCHAR(255),\n" +
                "        " + sagaSchema.associationValueColumn() + " VARCHAR(255),\n" +
                "        " + sagaSchema.sagaIdColumn() + " VARCHAR(255),\n" +
                "        " + sagaSchema.sagaTypeColumn() + " VARCHAR(255),\n" +
                "        PRIMARY KEY (id)\n" +
                "    );\n";
        return conn.prepareStatement(sql);
    }

    @Override
    public PreparedStatement sql_createTableSagaEntry(Connection conn) throws SQLException {
        return conn.prepareStatement("CREATE TABLE IF NOT EXISTS " + sagaSchema().sagaEntryTable() + " (\n" +
                                             "        " + sagaSchema.sagaIdColumn() + " VARCHAR(255) NOT NULL,\n" +
                                             "        " + sagaSchema.revisionColumn() + " VARCHAR(255),\n" +
                                             "        " + sagaSchema.sagaTypeColumn() + " VARCHAR(255),\n" +
                                             "        " + sagaSchema.serializedSagaColumn() + " bytea,\n" +
                                             "        PRIMARY KEY (" + sagaSchema.sagaIdColumn() + ")\n" +
                                             "    );");
    }

    @Override
    public PreparedStatement sql_loadSaga(Connection connection, String sagaId) throws SQLException {
        if (!exclusiveLoad) {
            return super.sql_loadSaga(connection, sagaId);
        }
        final String sql = "SELECT " + sagaSchema.serializedSagaColumn() + ", " + sagaSchema.sagaTypeColumn() + ", " +
                sagaSchema.revisionColumn() +
                " FROM " + sagaSchema().sagaEntryTable() +
                " WHERE " + sagaSchema.sagaIdColumn() + " = ?" +
                " FOR UPDATE";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, sagaId);
        return preparedStatement;
    }

    /**
     * Sets whether loading of Sagas should occur exclusively by a single node, by requiring a row lock from the
     * database.
     * <p>
     * If {@code true}, only one instance of the application may load a saga at a time. This may be used to serialize
     * event handling in sagas in multi-node configurations where <b>no</b>
     * {@link StreamingEventProcessor} is used. The given processor type caveat is
     * explained through the fact that a {@code StreamingEventProcessor} requires claimed segments to be able to perform
     * any event handling work. Furthermore, this segments originate from a shared resources in a distributed
     * environment.
     * <p>
     * As such, a Saga cannot be accessed concurrently through the {@code StreamingEventProcessor}. And hence, setting
     * {@code exclusiveLoad} to {@code true} would not change the underlying behavior at all.
     */
    public void setExclusiveLoad(boolean exclusiveLoad) {
        this.exclusiveLoad = exclusiveLoad;
    }
}
