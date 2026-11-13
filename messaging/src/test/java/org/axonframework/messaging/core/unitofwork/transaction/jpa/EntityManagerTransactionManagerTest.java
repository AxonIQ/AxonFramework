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

package org.axonframework.messaging.core.unitofwork.transaction.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.axonframework.common.jpa.EntityManagerExecutor;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityManagerTransactionManagerTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private EntityTransaction entityTransaction;

    private EntityManagerTransactionManager testSubject;

    @BeforeEach
    void setUp() {
        lenient().when(entityManager.getTransaction()).thenReturn(entityTransaction);
        testSubject = new EntityManagerTransactionManager(() -> entityManager);
    }

    @Nested
    class WhenNoActiveTransaction {

        @Test
        void startTransaction_beginsANewTransaction() {
            // when
            testSubject.startTransaction();

            // then
            verify(entityTransaction).begin();
        }

        @Test
        void startTransaction_commitIsApplied() {
            // given
            when(entityTransaction.isActive()).thenReturn(false, true);

            // when
            testSubject.startTransaction().commit();

            // then
            verify(entityTransaction).commit();
        }

        @Test
        void startTransaction_rollbackIsApplied() {
            // given
            when(entityTransaction.isActive()).thenReturn(false, true);

            // when
            testSubject.startTransaction().rollback();

            // then
            verify(entityTransaction).rollback();
        }

        @Test
        void startTransaction_commitIsNoOpWhenTransactionIsInactive() {
            // given — isActive() returns false both on begin-check and commit-guard
            when(entityTransaction.isActive()).thenReturn(false, false);

            // when
            testSubject.startTransaction().commit();

            // then
            verify(entityTransaction, never()).commit();
        }

        @Test
        void startTransaction_commitRollsBackWhenRollbackOnly() {
            // given — transaction is active on commit-guard, but marked rollback-only
            when(entityTransaction.isActive()).thenReturn(false, true);
            when(entityTransaction.getRollbackOnly()).thenReturn(true);

            // when
            testSubject.startTransaction().commit();

            // then
            verify(entityTransaction, never()).commit();
            verify(entityTransaction).rollback();
        }
    }

    @Nested
    class WhenTransactionIsAlreadyActive {

        @BeforeEach
        void setUp() {
            when(entityTransaction.isActive()).thenReturn(true);
        }

        @Test
        void startTransaction_doesNotBeginNewTransaction() {
            // when
            testSubject.startTransaction();

            // then
            verify(entityTransaction, never()).begin();
        }

        @Test
        void startTransaction_commitIsNoOp() {
            // when
            testSubject.startTransaction().commit();

            // then
            verify(entityTransaction, never()).commit();
        }

        @Test
        void startTransaction_rollbackIsNoOp() {
            // when
            testSubject.startTransaction().rollback();

            // then
            verify(entityTransaction, never()).rollback();
        }
    }

    @Nested
    class AttachToProcessingLifecycle {

        @Test
        void registersEntityManagerExecutorSupplierInContext(
                @Captor ArgumentCaptor<Consumer<ProcessingContext>> captor) {
            // given
            ProcessingLifecycle processingLifecycle = mock(ProcessingLifecycle.class);
            StubProcessingContext processingContext = new StubProcessingContext();

            // when
            testSubject.attachToProcessingLifecycle(processingLifecycle);
            verify(processingLifecycle).runOnPreInvocation(captor.capture());
            captor.getValue().accept(processingContext);

            // then
            Supplier<EntityManagerExecutor> supplier =
                    processingContext.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY);
            assertThat(supplier).isNotNull();
            assertThat(supplier.get()).isInstanceOf(EntityManagerExecutor.class);
        }

        @Test
        void commitsTransactionOnCommitPhase(
                @Captor ArgumentCaptor<Consumer<ProcessingContext>> captor) {
            // given
            when(entityTransaction.isActive()).thenReturn(false, true);
            ProcessingLifecycle processingLifecycle = mock(ProcessingLifecycle.class);
            StubProcessingContext processingContext = new StubProcessingContext();

            // when
            testSubject.attachToProcessingLifecycle(processingLifecycle);
            verify(processingLifecycle).runOnPreInvocation(captor.capture());
            captor.getValue().accept(processingContext);
            processingContext.moveToPhase(ProcessingLifecycle.DefaultPhases.COMMIT);

            // then
            verify(entityTransaction).commit();
        }

        @Test
        void rollsBackTransactionOnCommitPhaseWhenRollbackOnly(
                @Captor ArgumentCaptor<Consumer<ProcessingContext>> captor) {
            // given
            when(entityTransaction.isActive()).thenReturn(false, true);
            when(entityTransaction.getRollbackOnly()).thenReturn(true);
            ProcessingLifecycle processingLifecycle = mock(ProcessingLifecycle.class);
            StubProcessingContext processingContext = new StubProcessingContext();

            // when
            testSubject.attachToProcessingLifecycle(processingLifecycle);
            verify(processingLifecycle).runOnPreInvocation(captor.capture());
            captor.getValue().accept(processingContext);
            processingContext.moveToPhase(ProcessingLifecycle.DefaultPhases.COMMIT);

            // then
            verify(entityTransaction).rollback();
            verify(entityTransaction, never()).commit();
        }

        @Test
        void rollsBackTransactionOnError(
                @Captor ArgumentCaptor<Consumer<ProcessingContext>> preInvocationCaptor,
                @Captor ArgumentCaptor<ProcessingLifecycle.ErrorHandler> errorHandlerCaptor) {
            // given — spy needed because StubProcessingContext.onError is a no-op
            when(entityTransaction.isActive()).thenReturn(false, true);
            ProcessingLifecycle processingLifecycle = mock(ProcessingLifecycle.class);
            StubProcessingContext processingContext = spy(new StubProcessingContext());

            // when
            testSubject.attachToProcessingLifecycle(processingLifecycle);
            verify(processingLifecycle).runOnPreInvocation(preInvocationCaptor.capture());
            preInvocationCaptor.getValue().accept(processingContext);
            verify(processingContext).onError(errorHandlerCaptor.capture());
            errorHandlerCaptor.getValue()
                              .handle(processingContext, ProcessingLifecycle.DefaultPhases.INVOCATION,
                                      new RuntimeException("simulated error"));

            // then
            verify(entityTransaction).rollback();
            verify(entityTransaction, never()).commit();
        }

        @Test
        void canUseDifferentEntityManagerForTransactionAndExecutorWork(
                @Captor ArgumentCaptor<Consumer<ProcessingContext>> captor) {
            // given
            EntityManagerProvider entityManagerProvider = mock(EntityManagerProvider.class);
            EntityManager transactionEntityManager = mock(EntityManager.class);
            EntityManager executorEntityManager = mock(EntityManager.class);
            EntityTransaction transaction = mock(EntityTransaction.class);
            when(entityManagerProvider.getEntityManager()).thenReturn(transactionEntityManager, executorEntityManager);
            when(transactionEntityManager.getTransaction()).thenReturn(transaction);
            when(transaction.isActive()).thenReturn(false);

            EntityManagerTransactionManager localTestSubject = new EntityManagerTransactionManager(entityManagerProvider);
            ProcessingLifecycle processingLifecycle = mock(ProcessingLifecycle.class);
            StubProcessingContext processingContext = new StubProcessingContext();

            // when
            localTestSubject.attachToProcessingLifecycle(processingLifecycle);
            verify(processingLifecycle).runOnPreInvocation(captor.capture());
            captor.getValue().accept(processingContext);

            Supplier<EntityManagerExecutor> supplier =
                    processingContext.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY);
            EntityManager usedEntityManager = supplier.get()
                                                      .apply(entityManager -> entityManager)
                                                      .orTimeout(1, TimeUnit.SECONDS)
                                                      .join();

            // then
            assertThat(usedEntityManager).isSameAs(executorEntityManager);
            assertThat(usedEntityManager).isNotSameAs(transactionEntityManager);
            verify(transaction).begin();
        }
    }

    @Test
    void requiresSameThreadInvocationsReturnsTrue() {
        assertThat(testSubject.requiresSameThreadInvocations()).isTrue();
    }
}
