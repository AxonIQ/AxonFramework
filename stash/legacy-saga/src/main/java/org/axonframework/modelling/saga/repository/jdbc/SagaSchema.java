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

/**
 * SagaSchema allows specification of custom storage locations for the saga repositories.
 * <p/>
 * Usage depends on the underlying database - in general the form of "schema.tablename" should be sufficient.
 *
 * @author Jochen Munz
 * @since 2.4
 */
public class SagaSchema {

    private final String sagaEntryTable;
    private final String revisionColumn;
    private final String serializedSagaColumn;
    private final String associationValueEntryTable;
    private final String associationKeyColumn;
    private final String associationValueColumn;
    private final String sagaIdColumn;
    private final String sagaTypeColumn;

    /**
     * Initialize SagaSchema with default values.
     */
    public SagaSchema() {
        this(builder());
    }

    protected SagaSchema(Builder builder) {
        this.sagaEntryTable = builder.sagaEntryTable;
        this.revisionColumn = builder.revisionColumn;
        this.serializedSagaColumn = builder.serializedSagaColumn;
        this.associationValueEntryTable = builder.associationValueEntryTable;
        this.associationKeyColumn = builder.associationKeyColumn;
        this.associationValueColumn = builder.associationValueColumn;
        this.sagaIdColumn = builder.sagaIdColumn;
        this.sagaTypeColumn = builder.sagaTypeColumn;
    }

    /**
     * Returns a new {@link Builder} initialized with default settings.
     *
     * @return a new builder for the event schema
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the name of the sagaEntry table
     *
     * @return the name of the sagaEntry table
     */
    public String sagaEntryTable() {
        return sagaEntryTable;
    }

    /**
     * Returns the name of the revision column
     *
     * @return the name of the revision column
     */
    public String revisionColumn() {
        return revisionColumn;
    }

    /**
     * Returns the name of the serializedSaga column
     *
     * @return the name of the serializedSaga column
     */
    public String serializedSagaColumn() {
        return serializedSagaColumn;
    }

    /**
     * Returns the name of the associationValueEntry table
     *
     * @return the name of the associationValueEntry table
     */
    public String associationValueEntryTable() {
        return associationValueEntryTable;
    }

    /**
     * Returns the name of the associationKey column
     *
     * @return the name of the associationKey column
     */
    public String associationKeyColumn() {
        return associationKeyColumn;
    }

    /**
     * Returns the name of the associationValue column
     *
     * @return the name of the associationValue column
     */
    public String associationValueColumn() {
        return associationValueColumn;
    }

    /**
     * Returns the name of the sagaId column
     *
     * @return the name of the sagaId column
     */
    public String sagaIdColumn() {
        return sagaIdColumn;
    }

    /**
     * Returns the name of the sagaType column
     *
     * @return the name of the sagaType column
     */
    public String sagaTypeColumn() {
        return sagaTypeColumn;
    }

    /**
     * Builder for an {@link SagaSchema} that gets initialized with default values.
     */
    public static class Builder {

        private String sagaEntryTable = "SagaEntry";
        private String revisionColumn = "revision";
        private String serializedSagaColumn = "serializedSaga";
        private String associationValueEntryTable = "AssociationValueEntry";
        private String associationKeyColumn = "associationKey";
        private String associationValueColumn = "associationValue";
        private String sagaIdColumn = "sagaId";
        private String sagaTypeColumn = "sagaType";

        /**
         * Sets the name of the saga entry table. Defaults to 'SagaEntry'.
         *
         * @param sagaEntryTable the saga entry table name
         * @return the modified Builder instance
         */
        public Builder sagaEntryTable(String sagaEntryTable) {
            this.sagaEntryTable = sagaEntryTable;
            return this;
        }

        /**
         * Sets the name of the revision column. Defaults to 'revision'.
         *
         * @param columnName the name of the column
         * @return the modified Builder instance
         */
        public Builder revisionColumn(String columnName) {
            this.revisionColumn = columnName;
            return this;
        }

        /**
         * Sets the name of the serialized saga column. Defaults to 'serializedSaga'.
         *
         * @param columnName the name of the column
         * @return the modified Builder instance
         */
        public Builder serializedSagaColumn(String columnName) {
            this.serializedSagaColumn = columnName;
            return this;
        }

        /**
         * Sets the name of the association value entry table. Defaults to 'AssociationValueEntry'.
         *
         * @param associationValueEntryTable the association value entry table name
         * @return the modified Builder instance
         */
        public Builder associationValueEntryTable(String associationValueEntryTable) {
            this.associationValueEntryTable = associationValueEntryTable;
            return this;
        }

        /**
         * Sets the name of the association key column. Defaults to 'associationKey'.
         *
         * @param columnName the name of the column
         * @return the modified Builder instance
         */
        public Builder associationKeyColumn(String columnName) {
            this.associationKeyColumn = columnName;
            return this;
        }

        /**
         * Sets the name of the association value column. Defaults to 'associationValue'.
         *
         * @param columnName the name of the column
         * @return the modified Builder instance
         */
        public Builder associationValueColumn(String columnName) {
            this.associationValueColumn = columnName;
            return this;
        }

        /**
         * Sets the name of the saga id column. Defaults to 'sagaId'.
         *
         * @param columnName the name of the column
         * @return the modified Builder instance
         */
        public Builder sagaIdColumn(String columnName) {
            this.sagaIdColumn = columnName;
            return this;
        }

        /**
         * Sets the name of the saga type column. Defaults to 'sagaType'.
         *
         * @param columnName the name of the column
         * @return the modified Builder instance
         */
        public Builder sagaTypeColumn(String columnName) {
            this.sagaTypeColumn = columnName;
            return this;
        }

        /**
         * Builds a new {@link SagaSchema} from builder values.
         *
         * @return SagaSchema from this builder
         */
        public SagaSchema build() {
            return new SagaSchema(this);
        }

    }
}
