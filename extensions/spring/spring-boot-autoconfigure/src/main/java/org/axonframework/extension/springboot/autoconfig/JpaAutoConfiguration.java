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

import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.eventsourcing.eventstore.jpa.SQLErrorCodesResolver;
import org.axonframework.extension.springboot.TokenStoreProperties;
import org.axonframework.extension.springboot.util.RegisterDefaultEntities;
import org.axonframework.extension.springboot.util.jpa.ContainerManagedEntityManagerProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Autoconfiguration class for Axon's JPA specific infrastructure components.
 *
 * @author Allard Buijze
 * @author Simon Zambrovski
 * @since 3.0.3
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@ConditionalOnClass(EntityManagerFactory.class)
@ConditionalOnBean(EntityManagerFactory.class)
@EnableConfigurationProperties(TokenStoreProperties.class)
@RegisterDefaultEntities(packages = {
        "org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa",
})
public class JpaAutoConfiguration {


    /**
     * Retrieves an entity manager provider.
     *
     * @return An entity manager provider.
     */
    @Bean
    @ConditionalOnMissingBean
    public EntityManagerProvider entityManagerProvider() {
        return new ContainerManagedEntityManagerProvider();
    }

    /**
     * Builds a JPA Token Store.
     *
     * @param entityManagerFactory   an entity manager factory to retrieve connections
     * @param tokenStoreProperties   set of properties to configure the token store
     * @param converter              the converter to use for converting tokens
     * @return a JPA token store instance
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenStore tokenStore(EntityManagerFactory entityManagerFactory,
                                 TokenStoreProperties tokenStoreProperties,
                                 GeneralConverter converter) {
        var config = JpaTokenStoreConfiguration.DEFAULT.claimTimeout(tokenStoreProperties.getClaimTimeout());
        return new JpaTokenStore(new JpaTransactionalExecutorProvider(entityManagerFactory), converter, config);
    }

    /**
     * Provides a persistence exception resolver for a data source.
     *
     * Database-specific error codes are resolved when the application lifecycle starts, preventing database access
     * while the application context is being created.
     *
     * @param dataSource a data source configured to resolve exceptions for
     * @return a lifecycle-aware persistence exception resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistenceExceptionResolver persistenceExceptionResolver(DataSource dataSource) {
        return new LifecycleAwareSQLErrorCodesResolver(dataSource);
    }

    /**
     * Defers creation of the database-specific {@link SQLErrorCodesResolver} until Spring starts the application
     * lifecycle. This implementation is internal because it is an auto-configuration detail and should be consumed as
     * a {@link PersistenceExceptionResolver}.
     *
     * @author Christopher Friedrich
     */
    @Internal
    private static final class LifecycleAwareSQLErrorCodesResolver
            implements PersistenceExceptionResolver, SmartLifecycle {

        private final DataSource dataSource;
        private volatile @Nullable PersistenceExceptionResolver delegate;

        /**
         * Creates a lifecycle-aware resolver backed by the given {@code dataSource}.
         *
         * @param dataSource the data source used to resolve database-specific error codes
         */
        private LifecycleAwareSQLErrorCodesResolver(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public synchronized void start() {
            if (delegate != null) {
                return;
            }
            try {
                delegate = new SQLErrorCodesResolver(dataSource);
            } catch (SQLException e) {
                throw new AxonConfigurationException("Failed to initialize the persistence exception resolver.", e);
            }
        }

        @Override
        public synchronized void stop() {
            delegate = null;
        }

        @Override
        public boolean isRunning() {
            return delegate != null;
        }

        @Override
        public int getPhase() {
            return Phase.EXTERNAL_CONNECTIONS;
        }

        @Override
        public boolean isDuplicateKeyViolation(Exception exception) {
            PersistenceExceptionResolver resolver = delegate;
            return resolver != null && resolver.isDuplicateKeyViolation(exception);
        }
    }
}
