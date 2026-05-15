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
import org.axonframework.common.jdbc.JdbcSQLErrorCodesResolver;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.extension.springboot.TokenStoreProperties;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.TokenSchema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Autoconfiguration class for Axon's JDBC specific infrastructure components.
 * <p>
 * Registers a {@link ConnectionProvider}, a {@link TokenSchema}, a {@link TokenStore}, and a
 * {@link PersistenceExceptionResolver} when a {@link DataSource} bean is present in the application context.
 * All beans are conditional on the absence of user-supplied alternatives, so they can be overridden by providing
 * custom beans of the same types.
 * <p>
 * This configuration runs after {@link JpaAutoConfiguration} to allow JPA-specific beans (such as a JPA-based
 * {@link PersistenceExceptionResolver}) to take precedence.
 *
 * @author Allard Buijze
 * @since 3.1
 */
@AutoConfiguration(
        after = JpaAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        }
)
@ConditionalOnBean(DataSource.class)
@ConditionalOnClass(SpringDataSourceConnectionProvider.class)
@EnableConfigurationProperties(TokenStoreProperties.class)
public class JdbcAutoConfiguration {

    private final TokenStoreProperties tokenStoreProperties;

    /**
     * Creates a new {@link JdbcAutoConfiguration} with the given {@link TokenStoreProperties}.
     *
     * @param tokenStoreProperties properties used to configure the JDBC-backed {@link TokenStore}
     */
    public JdbcAutoConfiguration(TokenStoreProperties tokenStoreProperties) {
        this.tokenStoreProperties = tokenStoreProperties;
    }

    /**
     * Provides a {@link ConnectionProvider} backed by the application's {@link DataSource}.
     * <p>
     * Uses {@link SpringDataSourceConnectionProvider}, which is aware of Spring's transaction management and
     * returns the connection bound to the active transaction when one is present.
     *
     * @param dataSource the data source to obtain connections from
     * @return a connection provider for use in JDBC-based Axon components
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionProvider connectionProvider(DataSource dataSource) {
        return new SpringDataSourceConnectionProvider(dataSource);
    }

    /**
     * Provides a default {@link TokenSchema} describing the table and column names used by the JDBC token store.
     * <p>
     * Only created when neither a {@link TokenStore} nor a {@link TokenSchema} bean is already present, because a
     * custom {@link TokenStore} may use its own schema definition.
     *
     * @return a default {@link TokenSchema}
     */
    @Bean
    @ConditionalOnMissingBean({TokenStore.class, TokenSchema.class})
    public TokenSchema tokenSchema() {
        return new TokenSchema();
    }

    /**
     * Provides a JDBC-backed {@link TokenStore} for tracking event processor token persistence.
     * <p>
     * Uses a {@link JdbcTransactionalExecutorProvider} to participate in JDBC transactions and a
     * {@link JacksonConverter} to serialize tokens as JSON.
     *
     * @param dataSource              the data source used to execute token store queries
     * @param tokenSchema             the schema describing the token store table layout
     * @param converter               the converter to use for converting tokens
     * @return a configured {@link JdbcTokenStore}
     */
    @Bean
    @ConditionalOnMissingBean(TokenStore.class)
    public TokenStore tokenStore(DataSource dataSource,
                                 TokenSchema tokenSchema,
                                 GeneralConverter converter) {
        var config = JdbcTokenStoreConfiguration.DEFAULT
                .schema(tokenSchema)
                .claimTimeout(tokenStoreProperties.getClaimTimeout());
        return new JdbcTokenStore(new JdbcTransactionalExecutorProvider(dataSource), converter, config);
    }

    /**
     * Provides a {@link PersistenceExceptionResolver} that detects duplicate key violations from JDBC
     * {@link java.sql.SQLIntegrityConstraintViolationException} exceptions.
     * <p>
     * Conditional on the absence of an existing {@link PersistenceExceptionResolver} bean, since
     * {@link JpaAutoConfiguration} may already register one when JPA is on the classpath.
     *
     * @return a {@link JdbcSQLErrorCodesResolver} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistenceExceptionResolver persistenceExceptionResolver() {
        return new JdbcSQLErrorCodesResolver();
    }
}
