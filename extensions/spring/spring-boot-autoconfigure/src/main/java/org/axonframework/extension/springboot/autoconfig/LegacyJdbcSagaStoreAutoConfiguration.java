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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.jdbc.GenericSagaSqlSchema;
import org.axonframework.modelling.saga.repository.jdbc.JdbcSagaStore;
import org.axonframework.modelling.saga.repository.jdbc.SagaSqlSchema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Autoconfiguration class that registers a {@link JdbcSagaStore} when a {@link DataSource} is available, ported
 * from Axon Framework 4's JDBC saga store wiring onto {@code axon-legacy}'s {@link JdbcSagaStore} builder.
 * <p>
 * Provides two bean methods, matching Axon Framework 4's bean names verbatim:
 * <ul>
 *     <li>{@link #sagaStoreNoSchema(ConnectionProvider, GeneralConverter)}, using a {@link GenericSagaSqlSchema},
 *     when no {@link SagaSqlSchema} bean is declared;</li>
 *     <li>{@link #sagaStoreWithSchema(ConnectionProvider, GeneralConverter, SagaSqlSchema)}, using a
 *     user-declared {@link SagaSqlSchema}, so applications targeting a database dialect other than the generic one
 *     (see the {@code SagaSqlSchema} implementations for specific databases) can plug it in as a bean.</li>
 * </ul>
 * <p>
 * Runs after {@link JdbcAutoConfiguration}, so the {@link ConnectionProvider} bean it provides is available, and
 * after {@link LegacyJpaSagaStoreAutoConfiguration}, so a JPA-backed store, when applicable, takes precedence over
 * this JDBC-backed one -- both {@link ConditionalOnMissingBean} checks target {@link SagaStore}, so whichever
 * autoconfiguration runs first wins when an application has both an {@code EntityManagerFactory} and a
 * {@link DataSource}.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@AutoConfiguration(after = {JdbcAutoConfiguration.class, LegacyJpaSagaStoreAutoConfiguration.class})
@ConditionalOnClass(JdbcSagaStore.class)
@ConditionalOnBean(DataSource.class)
public class LegacyJdbcSagaStoreAutoConfiguration {

    /**
     * Provides a {@link JdbcSagaStore} using a {@link GenericSagaSqlSchema}, for use when no {@link SagaStore} and
     * no {@link SagaSqlSchema} bean are already present.
     *
     * @param connectionProvider the provider of the {@link java.sql.Connection} used to access the underlying
     *                           database
     * @param converter          the converter used to convert a saga instance to and from its serialized form
     * @return a JDBC-backed {@link SagaStore} using the generic SQL schema
     */
    @Bean
    @ConditionalOnMissingBean({SagaStore.class, SagaSqlSchema.class})
    public JdbcSagaStore sagaStoreNoSchema(ConnectionProvider connectionProvider, GeneralConverter converter) {
        return JdbcSagaStore.builder()
                             .connectionProvider(connectionProvider)
                             .converter(converter)
                             .sqlSchema(new GenericSagaSqlSchema())
                             .build();
    }

    /**
     * Provides a {@link JdbcSagaStore} using the given {@code sagaSqlSchema}, for use when a {@link SagaSqlSchema}
     * bean is present and no {@link SagaStore} bean is already present.
     *
     * @param connectionProvider the provider of the {@link java.sql.Connection} used to access the underlying
     *                           database
     * @param converter          the converter used to convert a saga instance to and from its serialized form
     * @param sagaSqlSchema      the user-declared SQL schema to use
     * @return a JDBC-backed {@link SagaStore} using the given SQL schema
     */
    @Bean
    @ConditionalOnMissingBean(SagaStore.class)
    @ConditionalOnBean(SagaSqlSchema.class)
    public JdbcSagaStore sagaStoreWithSchema(ConnectionProvider connectionProvider,
                                             GeneralConverter converter,
                                             SagaSqlSchema sagaSqlSchema) {
        return JdbcSagaStore.builder()
                             .connectionProvider(connectionProvider)
                             .converter(converter)
                             .sqlSchema(sagaSqlSchema)
                             .build();
    }
}
