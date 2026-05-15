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
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.eventsourcing.eventstore.jpa.SQLErrorCodesResolver;
import org.axonframework.extension.springboot.TokenStoreProperties;
import org.axonframework.extension.springboot.util.RegisterDefaultEntities;
import org.axonframework.extension.springboot.util.jpa.ContainerManagedEntityManagerProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
     * @param dataSource A data source configured to resolve exception for.
     * @return A working copy of Persistence Exception Resolver.
     * @throws SQLException on any construction errors.
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistenceExceptionResolver persistenceExceptionResolver(DataSource dataSource) throws SQLException {
        return new SQLErrorCodesResolver(dataSource);
    }
}