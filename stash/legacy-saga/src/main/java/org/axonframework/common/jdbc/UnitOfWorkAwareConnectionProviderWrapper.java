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
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;


import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wrapper for a ConnectionProvider that checks if a connection is already attached to the Unit of Work, favoring that
 * connection over creating a new one.
 *
 * @author Allard Buijze
 * @since 2.2
 */
public class UnitOfWorkAwareConnectionProviderWrapper implements ConnectionProvider {

    private static final String CONNECTION_RESOURCE_NAME = Connection.class.getName();

    private final ConnectionProvider delegate;

    /**
     * Initializes a ConnectionProvider, using given {@code delegate} to create a new instance, when on is not
     * already attached to the Unit of Work.
     *
     * @param delegate The connection provider creating connections, when required
     */
    public UnitOfWorkAwareConnectionProviderWrapper(ConnectionProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!CurrentUnitOfWork.isStarted() || CurrentUnitOfWork.get().phase()
                                                               .isAfter(LegacyUnitOfWork.Phase.PREPARE_COMMIT)) {
            return delegate.getConnection();
        }

        LegacyUnitOfWork<?> uow = CurrentUnitOfWork.get();
        Connection connection = uow.root().getResource(CONNECTION_RESOURCE_NAME);
        if (connection == null || connection.isClosed()) {
            final Connection delegateConnection = delegate.getConnection();
            connection = ConnectionWrapperFactory.wrap(delegateConnection,
                                                       UoWAttachedConnection.class,
                                                       new UoWAttachedConnectionImpl(delegateConnection),
                                                       new ConnectionWrapperFactory.NoOpCloseHandler());
            uow.root().resources().put(CONNECTION_RESOURCE_NAME, connection);
            uow.onCommit(u -> {
                Connection cx = u.root().getResource(CONNECTION_RESOURCE_NAME);
                try {
                    if (cx instanceof UoWAttachedConnection) {
                        ((UoWAttachedConnection) cx).forceCommit();
                    } else if (!cx.isClosed() && !cx.getAutoCommit()) {
                        cx.commit();
                    }
                } catch (SQLException e) {
                    throw new JdbcException("Unable to commit transaction", e);
                }
            });
            uow.onCleanup(u -> {
                Connection cx = u.root().getResource(CONNECTION_RESOURCE_NAME);
                JdbcUtils.closeQuietly(cx);
                if (cx instanceof UoWAttachedConnection) {
                    ((UoWAttachedConnection) cx).forceClose();
                }
            });
            uow.onRollback(u -> {
                Connection cx = u.root().getResource(CONNECTION_RESOURCE_NAME);
                try {
                    if (!cx.isClosed() && !cx.getAutoCommit()) {
                        cx.rollback();
                    }
                } catch (SQLException ex) {
                    if (u.getExecutionResult().isExceptionResult()) {
                        ExecutionException executeException = new ExecutionException(
                                "Unable to rollback transaction", u.getExecutionResult().getExceptionResult()
                        );
                        executeException.addSuppressed(ex);
                        throw executeException;
                    }
                    throw new JdbcException("Unable to rollback transaction", ex);
                }
            });
        }
        return connection;
    }

    private interface UoWAttachedConnection {

        void forceClose();

        void forceCommit() throws SQLException;
    }

    private static class UoWAttachedConnectionImpl implements UoWAttachedConnection {

        private final Connection delegateConnection;

        private UoWAttachedConnectionImpl(Connection delegateConnection) {
            this.delegateConnection = delegateConnection;
        }

        @Override
        public void forceClose() {
            JdbcUtils.closeQuietly(delegateConnection);
        }

        @Override
        public void forceCommit() throws SQLException {
            if (!delegateConnection.isClosed() && !delegateConnection.getAutoCommit()) {
                delegateConnection.commit();
            }
        }
    }
}
