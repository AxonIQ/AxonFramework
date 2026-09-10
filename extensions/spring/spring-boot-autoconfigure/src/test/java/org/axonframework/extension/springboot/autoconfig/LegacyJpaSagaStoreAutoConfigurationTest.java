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
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.modelling.saga.repository.jpa.JpaSagaStore;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link LegacyJpaSagaStoreAutoConfiguration}.
 *
 * @author Mateusz Nowak
 */
class LegacyJpaSagaStoreAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(JpaTestContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0");
    }

    @Test
    void registersAJpaSagaStoreWhenAnEntityManagerFactoryIsPresent() {
        testContext.run(context -> {
            assertThat(context).hasBean("sagaStore");
            assertThat(context.getBeanFactory().getType("sagaStore")).isEqualTo(JpaSagaStore.class);
        });
    }

    @Test
    void theSagaStoreCanPersistAndLoadASagaUsingTheRegisteredEntities() {
        testContext.run(context -> {
            @SuppressWarnings("unchecked")
            SagaStore<Object> sagaStore = context.getBean("sagaStore", SagaStore.class);
            TransactionTemplate transactionTemplate =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            String sagaIdentifier = UUID.randomUUID().toString();
            SimpleSaga saga = new SimpleSaga();
            saga.setState("started");
            AssociationValue associationValue = new AssociationValue("orderId", "order-1");

            transactionTemplate.executeWithoutResult(
                    status -> sagaStore.insertSaga(SimpleSaga.class, sagaIdentifier, saga, Set.of(associationValue))
            );

            SagaStore.Entry<SimpleSaga> loaded = transactionTemplate.execute(
                    status -> sagaStore.loadSaga(SimpleSaga.class, sagaIdentifier)
            );

            assertThat(loaded).isNotNull();
            assertThat(loaded.saga().getState()).isEqualTo("started");
            assertThat(loaded.associationValues()).containsExactly(associationValue);
        });
    }

    @Test
    void userDefinedSagaStoreWins() {
        testContext.withUserConfiguration(CustomSagaStoreContext.class).run(context -> {
            assertThat(context).doesNotHaveBean("sagaStore");
            assertThat(context.getBeanNamesForType(SagaStore.class)).containsExactly("customSagaStore");
        });
    }

    @Test
    void backsOffWithoutAnEntityManagerFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(NoJpaTestContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0")
                .run(context -> {
                    // The bean name "sagaStore" is shared with LegacySagaAutoConfiguration's in-memory fallback;
                    // without an EntityManagerFactory that fallback wins instead of a JpaSagaStore.
                    assertThat(context).hasBean("sagaStore");
                    assertThat(context.getBean("sagaStore")).isInstanceOf(InMemorySagaStore.class);
                });
    }

    /**
     * A saga type used purely as test fixture data, convertible to and from bytes by the default {@code
     * GeneralConverter}.
     */
    public static class SimpleSaga {

        private String state = "initial";

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class JpaTestContext {

        @Bean
        public DataSource dataSource() {
            String uniqueDbName = "jdbc:hsqldb:mem:legacyjpasagastoretest-" + System.nanoTime();
            DriverManagerDataSource driverManagerDataSource =
                    new DriverManagerDataSource(uniqueDbName, "sa", "");
            driverManagerDataSource.setDriverClassName("org.hsqldb.jdbcDriver");
            return driverManagerDataSource;
        }

        @Bean("entityManagerFactory")
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
        @DependsOn("entityManagerFactory")
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

    /**
     * Excludes Hibernate and the embedded {@code DataSource} autoconfiguration: this module has {@code hsqldb} and
     * {@code spring-boot-starter-data-jpa} on its test classpath, so without excluding them Spring Boot would
     * auto-configure an embedded {@code EntityManagerFactory} even though this context declares no JPA beans of its
     * own.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {HibernateJpaAutoConfiguration.class, DataSourceAutoConfiguration.class})
    static class NoJpaTestContext {

    }

    @Configuration
    static class CustomSagaStoreContext {

        @Bean
        public SagaStore<Object> customSagaStore() {
            return new InMemorySagaStore();
        }
    }
}
