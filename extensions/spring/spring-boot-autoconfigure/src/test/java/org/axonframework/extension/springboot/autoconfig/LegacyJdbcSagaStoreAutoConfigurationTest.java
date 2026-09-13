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
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.modelling.saga.repository.jdbc.GenericSagaSqlSchema;
import org.axonframework.modelling.saga.repository.jdbc.JdbcSagaStore;
import org.axonframework.modelling.saga.repository.jdbc.SagaSqlSchema;
import org.axonframework.modelling.saga.repository.jpa.JpaSagaStore;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Test class validating the {@link LegacyJdbcSagaStoreAutoConfiguration}.
 *
 * @author Mateusz Nowak
 */
class LegacyJdbcSagaStoreAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0");
    }

    @Test
    void registersAJdbcSagaStoreWithTheGenericSchemaWhenOnlyADataSourceIsPresent() {
        testContext.withUserConfiguration(DataSourceOnlyTestContext.class).run(context -> {
            assertThat(context).hasBean("sagaStoreNoSchema");
            assertThat(context.getBean("sagaStoreNoSchema")).isInstanceOf(JdbcSagaStore.class);
            assertThat(context).doesNotHaveBean("sagaStoreWithSchema");
            assertThat(context.getBeanNamesForType(SagaStore.class)).containsExactly("sagaStoreNoSchema");
        });
    }

    @Test
    void usesTheUserProvidedSagaSqlSchema() {
        testContext.withUserConfiguration(DataSourceOnlyTestContext.class, CustomSagaSqlSchemaContext.class)
                   .run(context -> {
                       assertThat(context).hasBean("sagaStoreWithSchema");
                       assertThat(context.getBean("sagaStoreWithSchema")).isInstanceOf(JdbcSagaStore.class);
                       assertThat(context).doesNotHaveBean("sagaStoreNoSchema");
                       assertThat(context.getBeanNamesForType(SagaStore.class)).containsExactly("sagaStoreWithSchema");
                   });
    }

    @Test
    void jpaSagaStoreTakesPrecedenceWhenBothEntityManagerFactoryAndDataSourcePresent() {
        testContext.withUserConfiguration(JpaAndDataSourceTestContext.class).run(context -> {
            assertThat(context.getBeanNamesForType(SagaStore.class)).containsExactly("sagaStore");
            assertThat(context.getBeanFactory().getType("sagaStore")).isEqualTo(JpaSagaStore.class);
            assertThat(context).doesNotHaveBean("sagaStoreNoSchema");
            assertThat(context).doesNotHaveBean("sagaStoreWithSchema");
        });
    }

    @Test
    void userDefinedSagaStoreWins() {
        testContext.withUserConfiguration(DataSourceOnlyTestContext.class, CustomSagaStoreContext.class)
                   .run(context -> {
                       assertThat(context).doesNotHaveBean("sagaStoreNoSchema");
                       assertThat(context).doesNotHaveBean("sagaStoreWithSchema");
                       assertThat(context.getBeanNamesForType(SagaStore.class)).containsExactly("customSagaStore");
                   });
    }

    /**
     * Excludes Hibernate and JPA repository autoconfiguration so a {@link DataSource} bean alone does not also pull
     * in an {@link EntityManagerFactory}, which would let {@link LegacyJpaSagaStoreAutoConfiguration}'s store take
     * precedence and mask the behavior under test here.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
    static class DataSourceOnlyTestContext {

        @Bean
        public DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class JpaAndDataSourceTestContext {

        @Bean
        public EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }

        /**
         * A real (schema-less) hsqldb {@link DataSource}, needed because {@link JpaAutoConfiguration}'s
         * {@code persistenceExceptionResolver} bean opens a connection on startup to resolve database-specific
         * error codes; a bare mock {@link DataSource} would fail that lifecycle step.
         */
        @Bean
        public DataSource dataSource() {
            String uniqueDbName = "jdbc:hsqldb:mem:legacyjdbcsagastoretest-precedence-" + System.nanoTime();
            DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource(uniqueDbName, "sa", "");
            driverManagerDataSource.setDriverClassName("org.hsqldb.jdbcDriver");
            return driverManagerDataSource;
        }
    }

    @Configuration
    static class CustomSagaSqlSchemaContext {

        @Bean
        public SagaSqlSchema sagaSqlSchema() {
            return new GenericSagaSqlSchema();
        }
    }

    @Configuration
    static class CustomSagaStoreContext {

        @Bean
        public SagaStore<Object> customSagaStore() {
            return new InMemorySagaStore();
        }
    }
}
