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

import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the auto-configuration of the transaction manager, and how it treats a unit of work that starts while a
 * transaction is already active.
 *
 * @author John Hendrikx
 */
class TransactionAutoConfigurationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:hsqldb:mem:" + UUID.randomUUID(), "SA", ""
        );

        testContext = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> dataSource)
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withConfiguration(AutoConfigurations.of(JdbcTransactionAutoConfiguration.class));
    }

    @Test
    void unitOfWorkTakingPartInAnActiveTransactionIsRejectedByDefault() {
        testContext.run(context -> {
            TransactionManager transactionManager = context.getBean(TransactionManager.class);

            assertThat(transactionManager).isInstanceOf(SpringTransactionManager.class);

            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
                assertThat(unitOfWorkOf(transactionManager).execute())
                    .failsWithin(TIMEOUT)
                    .withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(IllegalTransactionStateException.class)
            );
        });
    }

    @Test
    void unitOfWorkTakingPartInAnActiveTransactionIsAcceptedWhenAllowedByProperty() {
        testContext.withPropertyValues("axon.transaction.allow-joining-active-transaction=true").run(context -> {
            TransactionManager transactionManager = context.getBean(TransactionManager.class);

            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
                assertThat(unitOfWorkOf(transactionManager).execute()).succeedsWithin(TIMEOUT)
            );
        });
    }

    private UnitOfWork unitOfWorkOf(TransactionManager transactionManager) {
        UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();

        transactionManager.attachToProcessingLifecycle(unitOfWork);

        return unitOfWork;
    }
}
