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

/**
 * A JDBC-backed {@link org.axonframework.modelling.saga.repository.SagaStore}, together with the SQL dialects it can
 * speak.
 * <p>
 * {@link org.axonframework.modelling.saga.repository.jdbc.SagaSqlSchema} supplies the statements and
 * {@link org.axonframework.modelling.saga.repository.jdbc.SagaSchema} the table and column names, so both the dialect
 * and the naming can be swapped without touching the store. Implementations are provided for generic databases, HSQLDB,
 * PostgreSQL and Oracle 11.
 * <p>
 * The table and column layout is that of Axon Framework 4, so an existing saga table can be read and updated without
 * migration, provided its saga type column holds saga class names. See
 * {@link org.axonframework.modelling.saga.repository.jdbc.JdbcSagaStore} for when it might not.
 * <p>
 * These types carry the Axon Framework 4 API, to ease migration of projects that cannot move off it in one go. The
 * departures are those Axon Framework 5 forced by removing the {@code Serializer}:
 * {@code JdbcSagaStore.Builder#serializer} became {@code converter}, {@code JdbcSagaStore#setSerializer} is gone,
 * {@link org.axonframework.modelling.saga.repository.jdbc.SagaSqlSchema#readSerializedSaga} returns the raw bytes
 * rather than a serialized object, and {@code sql_updateSaga} no longer takes a revision. Gone with them is
 * {@code JdbcSagaStore.Builder#dataSource}, which built a connection provider that is not bound to the ambient
 * transaction; supply a {@code ConnectionProvider} instead.
 */
@NullMarked
package org.axonframework.modelling.saga.repository.jdbc;

import org.jspecify.annotations.NullMarked;
