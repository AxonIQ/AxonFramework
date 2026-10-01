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

package org.axonframework.messaging.core.unitofwork.transaction.jdbc;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.function.ThrowingFunction;
import org.axonframework.common.jdbc.ConnectionExecutor;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionalExecutorProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * A {@link TransactionalExecutorProvider} implementation for JDBC {@link Connection Connections} which
 * provides a {@link TransactionalExecutor}.
 * <p>
 * When a processing context is supplied, supplies the {@link TransactionalExecutor} it must contain.
 * If no processing context is supplied, creates an executor that executes the supplied functions
 * in their own transaction.
 *
 * @author John Hendrikx
 * @since 5.0.2
 */
public class JdbcTransactionalExecutorProvider implements TransactionalExecutorProvider<Connection> {

    /**
     * The resource key for the {@link ConnectionExecutor} supplier.
     */
    public static final ResourceKey<Supplier<ConnectionExecutor>> SUPPLIER_KEY =
            ResourceKey.withLabel(ConnectionExecutor.class.getSimpleName());

    private final DataSource dataSource;

    /**
     * Constructs a new instance.
     *
     * @param dataSource A JDBC {@link DataSource} used when no processing context is supplied, cannot be {@code null}.
     */
    public JdbcTransactionalExecutorProvider(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public TransactionalExecutor<Connection> getTransactionalExecutor(@Nullable ProcessingContext processingContext) {
        if (processingContext != null) {
            Supplier<ConnectionExecutor> executorSupplier = processingContext.getResource(SUPPLIER_KEY);

            if (executorSupplier == null) {
                throw new IllegalStateException("A connection executor must be present in the processing context.");
            }

            return executorSupplier.get();
        }

        return new TransactionalExecutor<>() {
            @Override
            public <R> CompletableFuture<R> apply(ThrowingFunction<Connection, R, Exception> function) {
                try {
                    return applyInTx(function, dataSource.getConnection());
                }
                catch (SQLException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }

            private <R> CompletableFuture<R> applyInTx(ThrowingFunction<Connection, R, Exception> function, Connection connection) {
                try {
                    connection.setAutoCommit(false);

                    R result = function.apply(connection);

                    connection.commit();

                    return CompletableFuture.completedFuture(result);
                }
                catch (Exception e) {
                    try {
                        connection.rollback();
                    }
                    catch (SQLException se) {
                        e.addSuppressed(se);
                    }

                    return CompletableFuture.failedFuture(e);
                }
                finally {
                    try {
                        connection.close();
                    }
                    catch (SQLException e) {
                        // Ignore, if closing fails, the system is likely in a bad state already
                    }
                }
            }
        };
    }
}
