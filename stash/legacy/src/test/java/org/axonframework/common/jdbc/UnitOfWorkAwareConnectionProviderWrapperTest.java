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

package org.axonframework.common.jdbc;

import org.axonframework.messaging.core.ExecutionException;
import org.axonframework.messaging.core.GenericResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.ExecutionResult;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnitOfWorkAwareConnectionProviderWrapperTest {

    private ConnectionProvider mockConnectionProvider;
    private Connection mockConnection;
    private UnitOfWorkAwareConnectionProviderWrapper testSubject;

    @BeforeEach
    void setUp() throws Exception {
        mockConnectionProvider = mock(ConnectionProvider.class);
        mockConnection = mock(Connection.class);
        when(mockConnectionProvider.getConnection()).thenReturn(mockConnection);

        testSubject = new UnitOfWorkAwareConnectionProviderWrapper(mockConnectionProvider);
    }

    @AfterEach
    void tearDown() {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    void connectionReturnedImmediatelyWhenNoActiveUnitOfWork() throws SQLException {
        Connection actual = testSubject.getConnection();
        assertSame(actual, mockConnection);
    }

    @Test
    void connectionIsWrappedWhenUnitOfWorkIsActive() throws SQLException {
        LegacyDefaultUnitOfWork<Message> uow = LegacyDefaultUnitOfWork.startAndGet(null);
        Connection actual = testSubject.getConnection();
        assertNotSame(actual, mockConnection);

        actual.close();

        verify(mockConnection, never()).close();

        uow.commit();

        verify(mockConnection).close();
    }

    @Test
    void wrappedConnectionBlocksCommitCallsUntilUnitOfWorkCommit() throws SQLException {
        LegacyDefaultUnitOfWork<Message> uow = LegacyDefaultUnitOfWork.startAndGet(null);
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.isClosed()).thenReturn(false);

        Connection actual = testSubject.getConnection();

        actual.commit();

        verify(mockConnection, never()).commit();
        verify(mockConnection, never()).close();

        uow.commit();

        InOrder inOrder = inOrder(mockConnection);
        inOrder.verify(mockConnection).commit();
        inOrder.verify(mockConnection).close();
    }

    @Test
    void wrappedConnectionRollsBackCallsWhenUnitOfWorkRollback() throws SQLException {
        LegacyDefaultUnitOfWork<Message> uow = LegacyDefaultUnitOfWork.startAndGet(null);
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.isClosed()).thenReturn(false);

        Connection actual = testSubject.getConnection();

        actual.close();
        actual.commit();

        verify(mockConnection, never()).commit();
        verify(mockConnection, never()).close();

        uow.rollback();

        InOrder inOrder = inOrder(mockConnection);
        inOrder.verify(mockConnection).rollback();
        inOrder.verify(mockConnection).close();
    }

    @Test
    void originalExceptionThrewWhenRollbackFailed() throws SQLException {
        LegacyDefaultUnitOfWork<Message> uow = new LegacyDefaultUnitOfWork<>(null) {
            @Override
            public ExecutionResult getExecutionResult() {
                return new ExecutionResult(
                        GenericResultMessage.asResultMessage(new IllegalArgumentException()));
            }
        };
        doThrow(SQLException.class).when(mockConnection)
                                   .rollback();

        uow.start();
        testSubject.getConnection();
        try {
            uow.rollback();
        } catch (ExecutionException e) {
            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
            assertEquals(SQLException.class, e.getSuppressed()[0].getClass());
        }
    }

    @Test
    void innerUnitOfWorkCommitDoesNotCloseConnection() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.isClosed()).thenReturn(false);

        LegacyDefaultUnitOfWork<Message> uow = LegacyDefaultUnitOfWork.startAndGet(null);
        Connection actualOuter = testSubject.getConnection();

        verify(mockConnectionProvider, times(1)).getConnection();

        LegacyDefaultUnitOfWork<Message> innerUow = LegacyDefaultUnitOfWork.startAndGet(null);
        Connection actualInner = testSubject.getConnection();

        verify(mockConnectionProvider, times(1)).getConnection();

        assertSame(actualOuter, actualInner);

        actualInner.close();
        actualInner.commit();

        verify(mockConnection, never()).commit();
        verify(mockConnection, never()).close();

        innerUow.commit();

        verify(mockConnection, never()).commit();
        verify(mockConnection, never()).close();

        uow.commit();

        verify(mockConnection).commit();
        verify(mockConnection).close();
    }
}
