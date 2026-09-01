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
 * migration.
 * <p>
 * These types carry the Axon Framework 4 API, to ease migration of projects that cannot move off it in one go. The
 * one departure is forced: Axon Framework 5 has no {@code Serializer}, so wherever one was accepted a
 * {@link org.axonframework.conversion.Converter} is taken instead.
 */
@NullMarked
package org.axonframework.modelling.saga.repository.jdbc;

import org.jspecify.annotations.NullMarked;
