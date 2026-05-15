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
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.SearchScope;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.FactoryBasedEntityManagerProvider;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurationDefaults;
import org.axonframework.eventsourcing.eventstore.EventCoordinator;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration;
import org.axonframework.eventsourcing.eventstore.jpa.JpaPollingEventCoordinator;
import org.axonframework.extension.springboot.JpaEventStorageEngineConfigurationProperties;
import org.axonframework.extension.springboot.util.RegisterDefaultEntities;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * Autoconfiguration class for Axon's JPA specific event store engine.
 *
 * @author Sara Pelligrini
 * @author Simon Zambrovski
 * @since 4.0
 */
@AutoConfiguration(afterName = {
        "io.axoniq.framework.springboot.autoconfig.AxonServerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.axonframework.extension.springboot.autoconfig.JpaAutoConfiguration"
})
@ConditionalOnBean({EntityManagerFactory.class, PlatformTransactionManager.class})
@ConditionalOnMissingBean(value = {EventStore.class, EventStorageEngine.class})
@RegisterDefaultEntities(packages = {
        "org.axonframework.eventsourcing.eventstore.jpa"
})
@EnableConfigurationProperties(JpaEventStorageEngineConfigurationProperties.class)
public class JpaEventStoreAutoConfiguration {

    /**
     * Creates an aggregate-based JPA event storage engine enhancer.
     *
     * @param entityManagerFactory                         An entity manager factory to provide to access the underlying
     *                                                     DB.
     * @param persistenceExceptionResolver                 A persistence exception resolver on duplicate errors.
     * @param jpaEventStorageEngineConfigurationProperties Spring properties to configure the JPA Event store Engine.
     * @return A configuration enhancer registering JPA Storage Engine ordered between Axon Server and In-Memory Storage
     * Engine.
     */
    @Bean
    public ConfigurationEnhancer aggregateBasedJpaEventStorageEngine(
            EntityManagerFactory entityManagerFactory,
            PersistenceExceptionResolver persistenceExceptionResolver,
            JpaEventStorageEngineConfigurationProperties jpaEventStorageEngineConfigurationProperties
    ) {
        return new AggregateBasedJpaEventStorageEngineConfigurationEnhancer(
                jpaEventStorageEngineConfigurationProperties,
                entityManagerFactory,
                persistenceExceptionResolver
        );
    }

    /**
     * Enhancer for registration of a bean definition creating a JPA Storage Engine.
     */
    public record AggregateBasedJpaEventStorageEngineConfigurationEnhancer(
            JpaEventStorageEngineConfigurationProperties properties,
            EntityManagerFactory factory,
            PersistenceExceptionResolver persistenceExceptionResolver
    ) implements ConfigurationEnhancer {

        @Override
        public void enhance(ComponentRegistry registry) {
            UnaryOperator<AggregateBasedJpaEventStorageEngineConfiguration> configurer = config ->
                    config
                            .batchSize(properties.batchSize())
                            .gapCleaningThreshold(properties.gapCleaningThreshold())
                            .gapTimeout(properties.gapTimeout())
                            .lowestGlobalSequence(properties.lowestGlobalSequence())
                            .maxGapOffset(properties.maxGapOffset())
                            .persistenceExceptionResolver(persistenceExceptionResolver)
                            .eventCoordinator(
                                    properties.pollingInterval() == 0 ?
                                            EventCoordinator.SIMPLE :
                                            new JpaPollingEventCoordinator(
                                                    new FactoryBasedEntityManagerProvider(factory),
                                                    Duration.ofMillis(properties.pollingInterval())
                                            )
                            );

            ComponentDefinition<EventStorageEngine> componentDefinition = ComponentDefinition.ofType(EventStorageEngine.class)
                                                                                             .withBuilder(configuration -> new AggregateBasedJpaEventStorageEngine(
                                                                                                     new JpaTransactionalExecutorProvider(
                                                                                                             factory),
                                                                                                     configuration.getComponent(
                                                                                                             EventConverter.class),
                                                                                                     configurer
                                                                                             ))
                                                                                             .onShutdown(Phase.INBOUND_EVENT_CONNECTORS,
                                                                                                         ese -> {
                                                                                                             if (ese instanceof AggregateBasedJpaEventStorageEngine engine) {
                                                                                                                 engine.close();
                                                                                                             }
                                                                                                         });

            registry.registerIfNotPresent(componentDefinition, SearchScope.ALL);
        }

        @Override
        public int order() {
            // we must be lower than the defaults in order to win.
            return EventSourcingConfigurationDefaults.ENHANCER_ORDER - 500;
        }
    }
}
