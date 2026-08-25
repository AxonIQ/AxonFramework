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

import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.conversion.SerializedObject;
import org.axonframework.conversion.SimpleSerializedObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Generic SagaSqlSchema implementation, for use in most databases. This implementation can be overridden to account
 * for differences in dialect between database implementations.
 *
 * @author Kristian Rosenvold
 * @author Allard Buijze
 * @since 2.2
 */
public class GenericSagaSqlSchema implements SagaSqlSchema {

    protected final SagaSchema sagaSchema;

    /**
     * Initialize a GenericSagaSqlSchema using default settings.
     */
    public GenericSagaSqlSchema() {
        this(new SagaSchema());
    }

    /**
     * Initialize a GenericSagaSqlSchema.
     * <p/>
     * Serialized data is stored using the given {@code sagaSchema}.
     *
     * @param sagaSchema The configuration to use for the initialization of the schema
     */
    public GenericSagaSqlSchema(SagaSchema sagaSchema) {
        this.sagaSchema = sagaSchema;
    }

    @Override
    public PreparedStatement sql_loadSaga(Connection connection, String sagaId) throws SQLException {
        final String sql = "SELECT " +
                String.join(", ", sagaSchema.serializedSagaColumn(), sagaSchema.sagaTypeColumn(), sagaSchema.revisionColumn())
                + " FROM " + sagaSchema.sagaEntryTable() +
                " WHERE " + sagaSchema.sagaIdColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, sagaId);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_removeAssocValue(Connection connection, String key, String value, String sagaType,
                                                  String sagaIdentifier) throws SQLException {
        final String sql = "DELETE FROM " + sagaSchema.associationValueEntryTable()
                + " WHERE " + sagaSchema.associationKeyColumn() + " = ? AND " + sagaSchema.associationValueColumn() + " = ?"
                + " AND " + sagaSchema.sagaTypeColumn() + " = ? AND " + sagaSchema.sagaIdColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, key);
        preparedStatement.setString(2, value);
        preparedStatement.setString(3, sagaType);
        preparedStatement.setString(4, sagaIdentifier);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_storeAssocValue(Connection connection, String key, String value, String sagaType,
                                                 String sagaIdentifier) throws SQLException {
        final String sql = "INSERT INTO " + sagaSchema.associationValueEntryTable()
                + " (" + String.join(", ", sagaSchema.associationKeyColumn(), sagaSchema.associationValueColumn(),
                sagaSchema.sagaTypeColumn(), sagaSchema.sagaIdColumn()) + ")"
                + " VALUES(?, ?, ?, ?)";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, key);
        preparedStatement.setString(2, value);
        preparedStatement.setString(3, sagaType);
        preparedStatement.setString(4, sagaIdentifier);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_findAssocSagaIdentifiers(Connection connection, String key, String value,
                                                          String sagaType) throws SQLException {
        final String sql = "SELECT " + sagaSchema.sagaIdColumn() + " FROM " + sagaSchema.associationValueEntryTable()
                + " WHERE " + sagaSchema.associationKeyColumn() + " = ?"
                + " AND " + sagaSchema.associationValueColumn() + " = ?"
                + " AND " + sagaSchema.sagaTypeColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, key);
        preparedStatement.setString(2, value);
        preparedStatement.setString(3, sagaType);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_findAssociations(Connection connection, String sagaIdentifier, String sagaType) throws SQLException {
        final String sql = "SELECT " + sagaSchema.associationKeyColumn() + ", " + sagaSchema.associationValueColumn() + " FROM " + sagaSchema.associationValueEntryTable()
                + " WHERE " + sagaSchema.sagaIdColumn() + " = ?"
                + " AND " + sagaSchema.sagaTypeColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, sagaIdentifier);
        preparedStatement.setString(2, sagaType);
        return preparedStatement;
    }

    @Override
    public String readToken(ResultSet resultSet) {
        // tokens not supported by this implementation
        return null;
    }

    @Override
    public Set<AssociationValue> readAssociationValues(ResultSet resultSet) throws SQLException {
        Set<AssociationValue> associationValues = new HashSet<>();
        while (resultSet.next()) {
            associationValues.add(new AssociationValue(resultSet.getString(1), resultSet.getString(2)));
        }
        return associationValues;
    }

    @Override
    public PreparedStatement sql_deleteSagaEntry(Connection connection, String sagaIdentifier) throws SQLException {
        final String sql = "DELETE FROM " + sagaSchema.sagaEntryTable() + " WHERE " + sagaSchema.sagaIdColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, sagaIdentifier);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_deleteAssociationEntries(Connection connection, String sagaIdentifier)
            throws SQLException {
        final String sql = "DELETE FROM " + sagaSchema.associationValueEntryTable() + " WHERE " + sagaSchema.sagaIdColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, sagaIdentifier);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_updateSaga(Connection connection, String sagaIdentifier, byte[] serializedSaga,
                                            String sagaType, String revision) throws SQLException {
        final String sql = "UPDATE " + sagaSchema.sagaEntryTable()
                + " SET " + sagaSchema.serializedSagaColumn() + " = ?, " + sagaSchema.revisionColumn() + " = ? WHERE "
                + sagaSchema.sagaIdColumn() + " = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setBytes(1, serializedSaga);
        preparedStatement.setString(2, revision);
        preparedStatement.setString(3, sagaIdentifier);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_storeSaga(Connection connection, String sagaIdentifier, String revision,
                                           String sagaType,
                                           byte[] serializedSaga) throws SQLException {
        final String sql = "INSERT INTO " + sagaSchema.sagaEntryTable() + "(" +
                String.join(", ", sagaSchema.sagaIdColumn(), sagaSchema.revisionColumn(), sagaSchema.sagaTypeColumn(),
                        sagaSchema.serializedSagaColumn()) + ") VALUES(?,?,?,?)";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(1, sagaIdentifier);
        preparedStatement.setString(2, revision);
        preparedStatement.setString(3, sagaType);
        preparedStatement.setBytes(4, serializedSaga);
        return preparedStatement;
    }

    @Override
    public PreparedStatement sql_createTableAssocValueEntry(Connection conn) throws SQLException {
        final String sql = "create table " + sagaSchema.associationValueEntryTable() + " (\n" +
                "        id int not null AUTO_INCREMENT,\n" +
                "        " + sagaSchema.associationKeyColumn() + " varchar(255),\n" +
                "        " + sagaSchema.associationValueColumn() + " varchar(255),\n" +
                "        " + sagaSchema.sagaIdColumn() + " varchar(255),\n" +
                "        " + sagaSchema.sagaTypeColumn() + " varchar(255),\n" +
                "        primary key (id)\n" +
                "    );\n";
        return conn.prepareStatement(sql);
    }

    @Override
    public PreparedStatement sql_createTableSagaEntry(Connection conn) throws SQLException {
        return conn.prepareStatement("create table " + sagaSchema.sagaEntryTable() + " (\n" +
                "        " + sagaSchema.sagaIdColumn() + " varchar(255) not null,\n" +
                "        " + sagaSchema.revisionColumn() + " varchar(255),\n" +
                "        " + sagaSchema.sagaTypeColumn() + " varchar(255),\n" +
                "        " + sagaSchema.serializedSagaColumn() + " blob,\n" +
                "        primary key (" + sagaSchema.sagaIdColumn() + ")\n" +
                "    );");
    }

    @Override
    public SerializedObject<byte[]> readSerializedSaga(ResultSet resultSet) throws SQLException {
        return new SimpleSerializedObject<>(resultSet.getBytes(1), byte[].class,
                resultSet.getString(2),
                resultSet.getString(3));
    }

    /**
     * Returns the {@link SagaSchema} used to configure this sql saga schema.
     *
     * @return the saga schema
     */
    public SagaSchema sagaSchema() {
        return sagaSchema;
    }
}
