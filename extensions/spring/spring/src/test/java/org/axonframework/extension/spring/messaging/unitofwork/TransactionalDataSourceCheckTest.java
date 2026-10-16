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

package org.axonframework.extension.spring.messaging.unitofwork;

import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link TransactionalDataSourceCheck}.
 */
class TransactionalDataSourceCheckTest {

    private EmbeddedDatabase dataSource;
    private EmbeddedDatabase otherDataSource;
    private LocalContainerEntityManagerFactoryBean entityManagerFactoryBean;

    @BeforeEach
    void setUp() {
        dataSource = embeddedDatabase();
        otherDataSource = embeddedDatabase();
    }

    @AfterEach
    void tearDown() {
        if (entityManagerFactoryBean != null) {
            entityManagerFactoryBean.destroy();
        }
        dataSource.shutdown();
        otherDataSource.shutdown();
    }

    @Nested
    class WhenTheTransactionManagerManagesTheDataSource {

        @Test
        void acceptsDataSourceTransactionManager() {
            assertNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    new DataSourceTransactionManager(dataSource), new SpringDataSourceConnectionProvider(dataSource)
            ));
        }

        @Test
        void acceptsTransactionAwareProxyOfTheManagedDataSource() {
            assertNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    new DataSourceTransactionManager(dataSource),
                    new SpringDataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource))
            ));
        }

        @Test
        void acceptsJpaTransactionManagerWithSpringManagedEntityManagerFactory() {
            PlatformTransactionManager transactionManager =
                    jpaTransactionManager(LocalContainerEntityManagerFactoryBean::getObject);

            assertNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    transactionManager, new SpringDataSourceConnectionProvider(dataSource)
            ));
        }
    }

    @Nested
    class WhenTheTransactionManagerDoesNotManageTheDataSource {

        @Test
        void reportsDataSourceTransactionManagerOnAnotherDataSource() {
            assertNotNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    new DataSourceTransactionManager(otherDataSource),
                    new SpringDataSourceConnectionProvider(dataSource)
            ));
        }

        @Test
        void reportsLazyConnectionProxyOfTheManagedDataSource() {
            assertNotNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    new DataSourceTransactionManager(dataSource),
                    new SpringDataSourceConnectionProvider(new LazyConnectionDataSourceProxy(dataSource))
            ));
        }

        @Test
        void reportsJpaTransactionManagerOnAnotherDataSource() {
            PlatformTransactionManager transactionManager =
                    jpaTransactionManager(LocalContainerEntityManagerFactoryBean::getObject);

            assertNotNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    transactionManager, new SpringDataSourceConnectionProvider(otherDataSource)
            ));
        }

        @Test
        void reportsJpaTransactionManagerWithNativeEntityManagerFactory() {
            PlatformTransactionManager transactionManager =
                    jpaTransactionManager(LocalContainerEntityManagerFactoryBean::getNativeEntityManagerFactory);

            assertNotNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    transactionManager, new SpringDataSourceConnectionProvider(dataSource)
            ));
        }
    }

    @Nested
    class WhenTheConfigurationCannotBeDetermined {

        @Test
        void acceptsUnknownTransactionManager() {
            assertNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    mock(PlatformTransactionManager.class), new SpringDataSourceConnectionProvider(dataSource)
            ));
        }

        @Test
        void acceptsOtherConnectionProvider() {
            assertNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    new DataSourceTransactionManager(otherDataSource), mock(ConnectionProvider.class)
            ));
        }

        @Test
        void acceptsMissingConnectionProvider() {
            assertNull(TransactionalDataSourceCheck.nonTransactionalReason(
                    new DataSourceTransactionManager(otherDataSource), null
            ));
        }
    }

    private static EmbeddedDatabase embeddedDatabase() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.HSQL)
                                            .generateUniqueName(true)
                                            .build();
    }

    private JpaTransactionManager jpaTransactionManager(
            Function<LocalContainerEntityManagerFactoryBean, EntityManagerFactory> entityManagerFactory
    ) {
        entityManagerFactoryBean = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactoryBean.setDataSource(dataSource);
        entityManagerFactoryBean.setPackagesToScan(getClass().getPackageName());
        entityManagerFactoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        entityManagerFactoryBean.afterPropertiesSet();
        JpaTransactionManager transactionManager =
                new JpaTransactionManager(entityManagerFactory.apply(entityManagerFactoryBean));
        transactionManager.afterPropertiesSet();
        return transactionManager;
    }
}
