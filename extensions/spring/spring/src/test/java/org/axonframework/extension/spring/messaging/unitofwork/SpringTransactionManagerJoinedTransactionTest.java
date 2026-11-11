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

import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the way {@link SpringTransactionManager} treats a processing lifecycle that starts while a transaction
 * is already active, using real Spring transaction management.
 *
 * @author John Hendrikx
 */
class SpringTransactionManagerJoinedTransactionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager springTransactionManager;
    private TransactionTemplate outerTransaction;

    @BeforeEach
    void beforeEach() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:hsqldb:mem:" + UUID.randomUUID(), "SA", ""
        );

        jdbcTemplate = new JdbcTemplate(dataSource);
        springTransactionManager = new DataSourceTransactionManager(dataSource);
        springTransactionManager.setNestedTransactionAllowed(true);
        outerTransaction = new TransactionTemplate(springTransactionManager);
    }

    private UnitOfWork unitOfWorkOf(SpringTransactionManager testSubject) {
        UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();

        testSubject.attachToProcessingLifecycle(unitOfWork);

        return unitOfWork;
    }

    private SpringTransactionManager managerWith(int propagation) {
        return new SpringTransactionManager(springTransactionManager, new DefaultTransactionDefinition(propagation));
    }

    @Nested
    class WhenATransactionIsAlreadyActive {

        @Test
        void unitOfWorkTakingPartInTheTransactionIsRejected() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_REQUIRED);

            // when / then
            outerTransaction.executeWithoutResult(status -> {
                assertThat(unitOfWorkOf(testSubject).execute())
                    .failsWithin(TIMEOUT)
                    .withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(IllegalTransactionStateException.class);

                assertThat(status.isRollbackOnly()).isFalse();
            });
        }

        @Test
        void unitOfWorkStartingANestedTransactionIsRejectedAndTheActiveTransactionStaysUsable() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_NESTED);

            // when / then
            outerTransaction.executeWithoutResult(status -> {
                assertThat(unitOfWorkOf(testSubject).execute())
                    .failsWithin(TIMEOUT)
                    .withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(IllegalTransactionStateException.class);

                assertThat(status.isRollbackOnly()).isFalse();
                assertThat(status.hasSavepoint()).isFalse();

                jdbcTemplate.execute("CREATE TABLE survivor (id INT)");
            });

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM survivor", Integer.class)).isZero();
        }

        @Test
        void unitOfWorkStartingANewTransactionIsAccepted() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            // when / then
            outerTransaction.executeWithoutResult(status ->
                assertThat(unitOfWorkOf(testSubject).execute()).succeedsWithin(TIMEOUT)
            );
        }
    }


    @Nested
    class WhenJoiningIsAllowed {

        @Test
        void unitOfWorkTakesPartInTheActiveTransactionAndLeavesItsCommitToIt() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_REQUIRED);

            testSubject.setAllowJoiningActiveTransaction(true);
            jdbcTemplate.execute("CREATE TABLE joined (id INT)");

            // when
            outerTransaction.executeWithoutResult(status -> {
                UnitOfWork unitOfWork = unitOfWorkOf(testSubject);

                unitOfWork.runOnInvocation(pc -> jdbcTemplate.update("INSERT INTO joined VALUES (1)"));

                assertThat(unitOfWork.execute()).succeedsWithin(TIMEOUT);

                status.setRollbackOnly();
            });

            // then
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM joined", Integer.class))
                .as("the unit of work did not commit, so rolling back the active transaction removes its work")
                .isZero();
        }

        @Test
        void unitOfWorkStartingANestedTransactionIsAccepted() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_NESTED);

            testSubject.setAllowJoiningActiveTransaction(true);

            // when / then
            outerTransaction.executeWithoutResult(status ->
                assertThat(unitOfWorkOf(testSubject).execute()).succeedsWithin(TIMEOUT)
            );
        }
    }

    @Nested
    class WhenNoTransactionIsActive {

        @Test
        void unitOfWorkIsInChargeOfItsOwnTransaction() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_REQUIRED);
            UnitOfWork unitOfWork = unitOfWorkOf(testSubject);
            AtomicBoolean activeDuringInvocation = new AtomicBoolean();

            unitOfWork.runOnInvocation(pc -> activeDuringInvocation.set(
                TransactionSynchronizationManager.isActualTransactionActive()
            ));

            // when
            assertThat(unitOfWork.execute()).succeedsWithin(TIMEOUT);

            // then
            assertThat(activeDuringInvocation).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }

        @Test
        void unitOfWorkWithADefinitionThatDoesNotRequireATransactionIsAccepted() {
            // given
            SpringTransactionManager testSubject = managerWith(TransactionDefinition.PROPAGATION_SUPPORTS);
            UnitOfWork unitOfWork = unitOfWorkOf(testSubject);
            AtomicBoolean activeDuringInvocation = new AtomicBoolean(true);

            unitOfWork.runOnInvocation(pc -> activeDuringInvocation.set(
                TransactionSynchronizationManager.isActualTransactionActive()
            ));

            // when / then
            assertThat(unitOfWork.execute()).succeedsWithin(TIMEOUT);
            assertThat(activeDuringInvocation).isFalse();
        }
    }
}
