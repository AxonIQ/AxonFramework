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

package org.axonframework.messaging.unitofwork;

import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.GenericResultMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.correlation.ThrowingCorrelationDataProvider;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
class AbstractUnitOfWorkTest {

    private List<PhaseTransition> phaseTransitions;
    private LegacyUnitOfWork<?> subject;

    @SuppressWarnings({"unchecked"})
    @BeforeEach
    void setUp() {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
        subject = spy(new LegacyDefaultUnitOfWork(
                new GenericEventMessage(new MessageType("event"), "Input 1")
        ) {
            @Override
            public String toString() {
                return "unitOfWork";
            }
        });
        phaseTransitions = new ArrayList<>();
        registerListeners(subject);
    }

    private void registerListeners(LegacyUnitOfWork<?> unitOfWork) {
        unitOfWork.onPrepareCommit(
                u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.PREPARE_COMMIT))
        );
        unitOfWork.onCommit(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.COMMIT)));
        unitOfWork.afterCommit(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.AFTER_COMMIT)));
        unitOfWork.onRollback(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.ROLLBACK)));
        unitOfWork.onCleanup(u -> phaseTransitions.add(new PhaseTransition(u, LegacyUnitOfWork.Phase.CLEANUP)));
    }

    @AfterEach
    void tearDown() {
        assertFalse(CurrentUnitOfWork.isStarted(), "A UnitOfWork was not properly cleared");
    }

    @Test
    void handlersForCurrentPhaseAreExecuted() {
        AtomicBoolean prepareCommit = new AtomicBoolean();
        AtomicBoolean commit = new AtomicBoolean();
        AtomicBoolean afterCommit = new AtomicBoolean();
        AtomicBoolean cleanup = new AtomicBoolean();
        subject.onPrepareCommit(u -> subject.onPrepareCommit(i -> prepareCommit.set(true)));
        subject.onCommit(u -> subject.onCommit(i -> commit.set(true)));
        subject.afterCommit(u -> subject.afterCommit(i -> afterCommit.set(true)));
        subject.onCleanup(u -> subject.onCleanup(i -> cleanup.set(true)));

        subject.start();
        subject.commit();

        assertTrue(prepareCommit.get());
        assertTrue(commit.get());
        assertTrue(afterCommit.get());
        assertTrue(cleanup.get());
    }

    @Test
    void executeTask() {
        Consumer task = mock(Consumer.class);
        doNothing().when(task).accept(any());
        subject.execute(task);
        InOrder inOrder = inOrder(task, subject);
        inOrder.verify(subject).start();
        inOrder.verify(task).accept(any());
        inOrder.verify(subject).commit();
        assertFalse(subject.isActive());
    }

    @Test
    void executeFailingTask() {
        Consumer task = mock(Consumer.class);
        MockException mockException = new MockException();
        Mockito.doThrow(mockException).when(task).accept(any());
        try {
            subject.execute(task);
        } catch (MockException e) {
            InOrder inOrder = inOrder(task, subject);
            inOrder.verify(subject).start();
            inOrder.verify(task).accept(any());
            inOrder.verify(subject).rollback(e);
            assertNotNull(subject.getExecutionResult());
            assertSame(mockException, subject.getExecutionResult().getExceptionResult());
            return;
        }
        throw new AssertionError();
    }

    @Test
    void executeTaskWithResult() throws Exception {
        Object taskResult = new Object();
        LegacyUnitOfWork.ProcessingContextCallable<Object> task = mock(LegacyUnitOfWork.ProcessingContextCallable.class);
        when(task.call(any())).thenReturn(taskResult);
        ResultMessage result = subject.executeWithResult(task);
        InOrder inOrder = inOrder(task, subject);
        inOrder.verify(subject).start();
        inOrder.verify(task).call(any());
        inOrder.verify(subject).commit();
        assertFalse(subject.isActive());
        assertSame(taskResult, result.payload());
        assertNotNull(subject.getExecutionResult());
        assertSame(taskResult, subject.getExecutionResult().getResult().payload());
    }

    @Test
    void executeTaskReturnsResultMessage() throws Exception {
        ResultMessage resultMessage = GenericResultMessage.asResultMessage(new Object());
        LegacyUnitOfWork.ProcessingContextCallable<ResultMessage> task = mock(LegacyUnitOfWork.ProcessingContextCallable.class);
        when(task.call(any())).thenReturn(resultMessage);
        ResultMessage actualResultMessage = subject.executeWithResult(task);
        assertSame(resultMessage, actualResultMessage);
    }

    @Test
    void attachedTransactionCommittedOnUnitOfWorkCommit() {
        TransactionManager transactionManager = mock(TransactionManager.class);
        Transaction transaction = mock(Transaction.class);
        when(transactionManager.startTransaction()).thenReturn(transaction);
        subject.attachTransaction(transactionManager);
        subject.start();
        verify(transactionManager).startTransaction();
        verify(transaction, never()).commit();
        subject.commit();
        verify(transaction).commit();
    }

    @Test
    void attachedTransactionRolledBackOnUnitOfWorkRollBack() {
        TransactionManager transactionManager = mock(TransactionManager.class);
        Transaction transaction = mock(Transaction.class);
        when(transactionManager.startTransaction()).thenReturn(transaction);
        subject.attachTransaction(transactionManager);
        subject.start();
        verify(transactionManager).startTransaction();
        verify(transaction, never()).commit();
        verify(transaction, never()).rollback();

        subject.rollback();
        verify(transaction).rollback();
        verify(transaction, never()).commit();
    }

    @Test
    void unitOfWorkIsRolledBackWhenTransactionFailsToStart() {
        TransactionManager transactionManager = mock(TransactionManager.class);
        when(transactionManager.startTransaction()).thenThrow(new MockException());
        try {
            subject.attachTransaction(transactionManager);
            fail("Expected MockException to be propagated");
        } catch (Exception e) {
            // expected
        }
        verify(subject).rollback(isA(MockException.class));
    }

    @Test
    void whenGettingCorrelationMetaThrows_thenCatchExceptions() {
        subject.registerCorrelationDataProvider(new ThrowingCorrelationDataProvider());
        Metadata correlationData = subject.getCorrelationData();
        assertNotNull(correlationData);
    }

    private static class PhaseTransition {

        private final LegacyUnitOfWork.Phase phase;
        private final LegacyUnitOfWork<?> unitOfWork;

        public PhaseTransition(LegacyUnitOfWork<?> unitOfWork, LegacyUnitOfWork.Phase phase) {
            this.unitOfWork = unitOfWork;
            this.phase = phase;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PhaseTransition that = (PhaseTransition) o;
            return Objects.equals(phase, that.phase) &&
                    Objects.equals(unitOfWork, that.unitOfWork);
        }

        @Override
        public int hashCode() {
            return Objects.hash(phase, unitOfWork);
        }

        @Override
        public String toString() {
            return unitOfWork + " " + phase;
        }
    }
}
