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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.jdbc.ConnectionExecutor;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jpa.EntityManagerExecutor;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.conversion.CachingSupplier;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * TransactionManager implementation that uses a {@link org.springframework.transaction.PlatformTransactionManager} as
 * underlying transaction manager. This implementation provides a {@link ConnectionExecutor} and/or
 * {@link EntityManagerExecutor} when attached to a processing lifecycle, if available.
 * <p>
 * Every connection obtained through the {@link ConnectionProvider} is checked: a connection that auto-commits is not
 * part of the transaction of the {@code PlatformTransactionManager}, so every statement run on it would be committed on
 * its own. Such a connection is refused with a {@link NonTransactionalConnectionException}. This applies to all
 * components that use the connection of the processing context, such as the JDBC token store. It typically means that
 * the connection provider uses a different {@code DataSource} instance than the one managed by the transaction manager.
 * <p>
 * The check is skipped when the {@code PlatformTransactionManager} is a
 * {@link org.springframework.transaction.jta.JtaTransactionManager JtaTransactionManager}, because connections of XA
 * data sources do not reliably report whether they take part in a global transaction. A transaction manager that merely
 * delegates to a {@code JtaTransactionManager} is not recognized as such, and is checked.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class SpringTransactionManager implements TransactionManager {

    private static final String NON_TRANSACTIONAL_CONNECTION_MESSAGE =
            "The connection returned by the ConnectionProvider is not part of a transaction (auto-commit is enabled), "
                    + "so every statement would be committed on its own. Make sure the ConnectionProvider returns "
                    + "connections of the DataSource that is managed by the transaction manager in use, for example by "
                    + "using the same DataSource instance for both, and that the transaction manager can actually "
                    + "expose that DataSource.";

    private final PlatformTransactionManager transactionManager;
    private final EntityManagerProvider entityManagerProvider;
    private final ConnectionProvider connectionProvider;
    private final TransactionDefinition transactionDefinition;

    /**
     * Constructs a new instance.
     *
     * @param transactionManager    The transaction manager to use.
     * @param entityManagerProvider The optional entity manager provider to use.
     * @param connectionProvider    The optional connection provider to use.
     * @param transactionDefinition The optional definition for transactions to create.
     */
    public SpringTransactionManager(PlatformTransactionManager transactionManager,
                                    @Nullable EntityManagerProvider entityManagerProvider,
                                    @Nullable ConnectionProvider connectionProvider,
                                    @Nullable TransactionDefinition transactionDefinition) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.entityManagerProvider = entityManagerProvider;
        this.connectionProvider = connectionProvider;
        this.transactionDefinition = transactionDefinition;
    }

    /**
     * Constructs a new instance.
     *
     * @param transactionManager    The transaction manager to use.
     * @param transactionDefinition The optional definition for transactions to create.
     */
    public SpringTransactionManager(PlatformTransactionManager transactionManager,
                                    @Nullable TransactionDefinition transactionDefinition) {
        this(transactionManager, null, null, transactionDefinition);
    }

    /**
     * Initializes the SpringTransactionManager with the given {@code transactionManager} and the default
     * transaction definition.
     *
     * @param transactionManager the transaction manager to use.
     * @param entityManagerProvider The optional entity manager provider to use.
     * @param connectionProvider    The optional connection provider to use.
     */
    public SpringTransactionManager(PlatformTransactionManager transactionManager,
                                    @Nullable EntityManagerProvider entityManagerProvider,
                                    @Nullable ConnectionProvider connectionProvider) {
        this(transactionManager, entityManagerProvider, connectionProvider, new DefaultTransactionDefinition());
    }

    /**
     * Constructs a new instance.
     *
     * @param transactionManager The transaction manager to use.
     */
    public SpringTransactionManager(PlatformTransactionManager transactionManager) {
        this(transactionManager, null, null, new DefaultTransactionDefinition());
    }

    @Override
    public void attachToProcessingLifecycle(ProcessingLifecycle processingLifecycle) {
        processingLifecycle.runOnPreInvocation(pc -> {
            Transaction transaction = startTransaction();

            if (entityManagerProvider != null) {
                pc.putResource(
                    JpaTransactionalExecutorProvider.SUPPLIER_KEY,
                    CachingSupplier.of(() -> new EntityManagerExecutor(entityManagerProvider))
                );
            }

            if (connectionProvider != null) {
                ConnectionProvider provider = transactionalConnectionProvider();

                pc.putResource(
                    JdbcTransactionalExecutorProvider.SUPPLIER_KEY,
                    CachingSupplier.of(() -> new ConnectionExecutor(provider))
                );
            }

            pc.runOnCommit(p -> transaction.commit());
            pc.onError((p, phase, e) -> transaction.rollback());
        });
    }

    /*
     * Returns a provider that refuses connections that auto-commit. Such a connection is not part of the transaction
     * of the transaction manager, and every statement run on it would be committed on its own.
     *
     * A JTA transaction manager is exempt: the connections of an XA data source do not reliably report whether they
     * take part in a global transaction. For example, an Atomikos XA connection reports auto-commit inside a JTA
     * transaction until its first statement has run.
     */
    private ConnectionProvider transactionalConnectionProvider() {
        if (transactionManager instanceof JtaTransactionManager) {
            return connectionProvider;
        }

        return () -> {
            Connection connection = connectionProvider.getConnection();

            try {
                if (connection.getAutoCommit()) {
                    throw new NonTransactionalConnectionException(NON_TRANSACTIONAL_CONNECTION_MESSAGE);
                }
            }
            catch (SQLException | RuntimeException e) {
                closeQuietly(connection, e);

                throw e;
            }

            return connection;
        };
    }

    private static void closeQuietly(Connection connection, Exception cause) {
        try {
            connection.close();
        }
        catch (SQLException | RuntimeException e) {
            cause.addSuppressed(e);
        }
    }

    @Override
    public Transaction startTransaction() {
        TransactionStatus status = transactionManager.getTransaction(transactionDefinition);
        return new Transaction() {
            @Override
            public void commit() {
                commitTransaction(status);
            }

            @Override
            public void rollback() {
                rollbackTransaction(status);
            }
        };
    }

    @Override
    public boolean requiresSameThreadInvocations() {
        return true;
    }

    /**
     * Commits the transaction with given {@code status} if the transaction is new and not completed.
     *
     * @param status The status of the transaction to commit
     */
    protected void commitTransaction(TransactionStatus status) {
        if (status.isNewTransaction() && !status.isCompleted()) {
            transactionManager.commit(status);
        }
    }

    /**
     * Rolls back the transaction with given {@code status} if the transaction is new and not completed.
     *
     * @param status The status of the transaction to roll back
     */
    protected void rollbackTransaction(TransactionStatus status) {
        if (status.isNewTransaction() && !status.isCompleted()) {
            transactionManager.rollback(status);
        }
    }
}
