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

import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating how the {@link SpringTransactionManager} applies its {@link NonTransactionalConnectionPolicy}.
 */
class SpringTransactionManagerNonTransactionalConnectionTest {

    private EmbeddedDatabase dataSource;
    private EmbeddedDatabase otherDataSource;

    @BeforeEach
    void setUp() {
        dataSource = embeddedDatabase();
        otherDataSource = embeddedDatabase();
    }

    @AfterEach
    void tearDown() {
        dataSource.shutdown();
        otherDataSource.shutdown();
    }

    @Nested
    class WhenTheConnectionProviderIsNotPartOfTheTransaction {

        @Test
        void failPolicyRefusesTheConfiguration() {
            NonTransactionalConnectionProviderException exception = assertThrows(
                    NonTransactionalConnectionProviderException.class,
                    () -> transactionManager(otherDataSource, NonTransactionalConnectionPolicy.FAIL)
            );
            assertNotNull(exception.reason());
        }

        @Test
        void warnPolicyAcceptsTheConfiguration() {
            assertDoesNotThrow(() -> transactionManager(otherDataSource, NonTransactionalConnectionPolicy.WARN));
        }

        @Test
        void ignorePolicyAcceptsTheConfiguration() {
            assertDoesNotThrow(() -> transactionManager(otherDataSource, NonTransactionalConnectionPolicy.IGNORE));
        }

        @Test
        void existingConstructorsWarnInsteadOfFailing() {
            assertDoesNotThrow(() -> new SpringTransactionManager(
                    new DataSourceTransactionManager(otherDataSource),
                    null,
                    new SpringDataSourceConnectionProvider(dataSource)
            ));
        }
    }

    @Nested
    class WhenTheConnectionProviderIsPartOfTheTransaction {

        @Test
        void failPolicyAcceptsTheConfiguration() {
            assertDoesNotThrow(() -> transactionManager(dataSource, NonTransactionalConnectionPolicy.FAIL));
        }
    }

    private SpringTransactionManager transactionManager(EmbeddedDatabase managedDataSource,
                                                        NonTransactionalConnectionPolicy policy) {
        return new SpringTransactionManager(new DataSourceTransactionManager(managedDataSource),
                                            null,
                                            new SpringDataSourceConnectionProvider(dataSource),
                                            new DefaultTransactionDefinition(),
                                            policy);
    }

    private static EmbeddedDatabase embeddedDatabase() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.HSQL)
                                            .generateUniqueName(true)
                                            .build();
    }
}
