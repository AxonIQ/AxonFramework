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

package org.axonframework.extension.springboot.eventsourcing.eventstore.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.FactoryBasedEntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStoreTestSuite;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.JpaPollingEventCoordinator;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.extension.springboot.autoconfig.JpaTransactionAutoConfiguration;
import org.axonframework.extension.springboot.eventsourcing.eventstore.jpa.AggregateBasedJpaStorageEngineBackedEventStoreIT.TestConfig;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.Duration;
import javax.sql.DataSource;

/**
 * Test class validating the {@link AggregateBasedJpaEventStorageEngine}.
 *
 * @author John Hendrikx
 */
@SpringBootTest(classes = TestConfig.class)
@ImportAutoConfiguration(JpaTransactionAutoConfiguration.class)
class AggregateBasedJpaStorageEngineBackedEventStoreIT extends
        StorageEngineBackedEventStoreTestSuite<AggregateBasedJpaEventStorageEngine> {

    private static AggregateBasedJpaEventStorageEngine engine;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private SpringTransactionManager springTransactionManager;

    @NonNull
    @Override
    protected AggregateBasedJpaEventStorageEngine getStorageEngine(@NonNull EventConverter converter) {
        if(engine == null) {
            FactoryBasedEntityManagerProvider entityManagerProvider = new FactoryBasedEntityManagerProvider(entityManagerFactory);

            engine = new AggregateBasedJpaEventStorageEngine(
                new JpaTransactionalExecutorProvider(entityManagerFactory),
                converter,
                config -> config
                    .eventCoordinator(new JpaPollingEventCoordinator(entityManagerProvider, Duration.ofMillis(500)))
                    .persistenceExceptionResolver(new PersistenceExceptionResolver() {
                        @Override
                        public boolean isDuplicateKeyViolation(@NonNull Exception exception) {
                            return causeIsEntityExistsException(exception);
                        }

                        private boolean causeIsEntityExistsException(Throwable exception) {
                            return exception instanceof java.sql.SQLIntegrityConstraintViolationException
                                || (exception.getCause() != null
                                    && causeIsEntityExistsException(exception.getCause()));
                        }
                    })
            );
        }

        return engine;
    }

    @NonNull
    @Override
    protected UnitOfWork unitOfWork() {
        TransactionalUnitOfWorkFactory factory = new TransactionalUnitOfWorkFactory(
                springTransactionManager,
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );

        return factory.create();
    }

    @Configuration
    public static class TestConfig {

        @Configuration
        public static class PersistenceConfig {

            @PersistenceContext
            private EntityManager entityManager;

            @Bean
            public EntityManagerProvider entityManagerProvider() {
                return new SimpleEntityManagerProvider(entityManager);
            }
        }

        @Bean
        public DataSource dataSource() {
            String uniqueDbName = "jdbc:hsqldb:mem:aggregatebasedjpaeventstorageenginetest-" + System.nanoTime();
            DriverManagerDataSource driverManagerDataSource =
                    new DriverManagerDataSource(uniqueDbName, "sa", "password");
            driverManagerDataSource.setDriverClassName("org.hsqldb.jdbcDriver");
            return driverManagerDataSource;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean entityManagerFactoryBean =
                    new LocalContainerEntityManagerFactoryBean();
            entityManagerFactoryBean.setPersistenceUnitName("integrationtest");

            HibernateJpaVendorAdapter jpaVendorAdapter = new HibernateJpaVendorAdapter();
            jpaVendorAdapter.setGenerateDdl(true);
            jpaVendorAdapter.setShowSql(false);

            entityManagerFactoryBean.setJpaVendorAdapter(jpaVendorAdapter);
            entityManagerFactoryBean.setDataSource(dataSource);

            return entityManagerFactoryBean;
        }

        @Bean
        public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
                                                        DataSource dataSource) {
            JpaTransactionManager jpaTransactionManager = new JpaTransactionManager(entityManagerFactory);
            jpaTransactionManager.setDataSource(dataSource);
            return jpaTransactionManager;
        }

        @Bean
        public static PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
            return new PersistenceAnnotationBeanPostProcessor();
        }
    }
}
