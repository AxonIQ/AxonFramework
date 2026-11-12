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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.sql.Connection;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
@ExtendWith(MockitoExtension.class)
class SpringTransactionManagerTest {

    @Mock private TransactionStatus underlyingTransactionStatus;
    @Mock private PlatformTransactionManager transactionManager;

    private SpringTransactionManager testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new SpringTransactionManager(transactionManager);
        when(transactionManager.getTransaction(isA(TransactionDefinition.class)))
                .thenReturn(underlyingTransactionStatus);
        when(underlyingTransactionStatus.isNewTransaction()).thenReturn(true);
    }

    @Test
    void manageTransaction_CustomTransactionStatus() {
        testSubject = new SpringTransactionManager(transactionManager, mock(TransactionDefinition.class));
        testSubject.startTransaction().commit();

        verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(underlyingTransactionStatus);
    }

    @Test
    void manageTransaction_DefaultTransactionStatus() {
        testSubject.startTransaction().commit();

        verify(transactionManager).getTransaction(isA(DefaultTransactionDefinition.class));
        verify(transactionManager).commit(underlyingTransactionStatus);
    }

    @Test
    void commitTransaction_NoCommitOnInactiveTransaction() {
        Transaction transaction = testSubject.startTransaction();
        when(underlyingTransactionStatus.isCompleted()).thenReturn(true);
        transaction.commit();

        verify(transactionManager, never()).commit(underlyingTransactionStatus);
    }

    @Test
    void commitTransaction_NoRollbackOnInactiveTransaction() {
        Transaction transaction = testSubject.startTransaction();
        when(underlyingTransactionStatus.isCompleted()).thenReturn(true);
        transaction.rollback();

        verify(transactionManager, never()).rollback(underlyingTransactionStatus);
    }

    @Test
    void commitTransaction_NoCommitOnNestedTransaction() {
        Transaction transaction = testSubject.startTransaction();
        when(underlyingTransactionStatus.isNewTransaction()).thenReturn(false);
        transaction.commit();

        verify(transactionManager, never()).commit(underlyingTransactionStatus);
    }

    @Test
    void commitTransaction_NoRollbackOnNestedTransaction() {
        Transaction transaction = testSubject.startTransaction();
        when(underlyingTransactionStatus.isNewTransaction()).thenReturn(false);
        transaction.rollback();

        verify(transactionManager, never()).rollback(underlyingTransactionStatus);
    }

    @Test
    void shouldAttachToProcessingLifecycle(@Captor ArgumentCaptor<Consumer<ProcessingContext>> captor) throws Exception {
        ConnectionProvider connectionProvider = mock(ConnectionProvider.class);

        EntityManagerProvider entityManagerProvider = mock(EntityManagerProvider.class);
        EntityManager entityManager = mock(EntityManager.class);

        when(connectionProvider.getConnection()).thenReturn(mock(Connection.class));
        when(entityManagerProvider.getEntityManager()).thenReturn(entityManager);
        when(entityManager.getEntityManagerFactory()).thenReturn(mock(EntityManagerFactory.class));

        SpringTransactionManager txManager = new SpringTransactionManager(transactionManager, entityManagerProvider, connectionProvider);
        ProcessingLifecycle processingLifecycle = mock(ProcessingLifecycle.class);
        StubProcessingContext processingContext = new StubProcessingContext();

        txManager.attachToProcessingLifecycle(processingLifecycle);

        verify(processingLifecycle).runOnPreInvocation(captor.capture());

        Consumer<ProcessingContext> preInvocationConsumer = captor.getValue();

        preInvocationConsumer.accept(processingContext);

        assertThat(processingContext.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY)).isNotNull();
        assertThat(processingContext.getResource(JdbcTransactionalExecutorProvider.SUPPLIER_KEY)).isNotNull();

        processingContext.moveToPhase(ProcessingLifecycle.DefaultPhases.COMMIT);

        verify(transactionManager).commit(underlyingTransactionStatus);
    }
}
