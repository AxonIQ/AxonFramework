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

package org.axonframework.extension.micronaut.messaging.unitofwork;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.junit.jupiter.api.*;
import io.micronaut.transaction.SynchronousTransactionManager;
import io.micronaut.transaction.TransactionDefinition;
import io.micronaut.transaction.TransactionStatus;
import io.micronaut.transaction.support.DefaultTransactionDefinition;

import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
@MicronautTest(startApplication = false)
class MicronautTransactionManagerTest {

    private MicronautTransactionManager<Object> testSubject;
    private SynchronousTransactionManager<Object> transactionManager;
    private TransactionStatus<Object> underlyingTransactionStatus;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        underlyingTransactionStatus = (TransactionStatus<Object>)mock(TransactionStatus.class);
        transactionManager = (SynchronousTransactionManager<Object>)mock(SynchronousTransactionManager.class);
        testSubject = new MicronautTransactionManager<>(transactionManager);
        when(transactionManager.getTransaction(isA(TransactionDefinition.class)))
                .thenReturn(underlyingTransactionStatus);
        when(underlyingTransactionStatus.isNewTransaction()).thenReturn(true);
    }

    @Test
    void manageTransaction_CustomTransactionStatus() {
        testSubject = new MicronautTransactionManager<>(transactionManager, mock(TransactionDefinition.class));
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
}
