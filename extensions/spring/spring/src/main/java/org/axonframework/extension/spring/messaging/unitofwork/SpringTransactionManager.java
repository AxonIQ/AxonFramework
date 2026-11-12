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
import org.axonframework.common.jdbc.JdbcException;
import org.axonframework.common.jdbc.PhysicalConnectionAware;
import org.axonframework.common.jdbc.PhysicalConnectionAwareExecutor;
import org.axonframework.common.jpa.EntityManagerExecutor;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.conversion.CachingSupplier;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TransactionManager implementation that uses a {@link org.springframework.transaction.PlatformTransactionManager} as
 * underlying transaction manager. This implementation provides a {@link ConnectionExecutor} and/or
 * {@link EntityManagerExecutor} when attached to a processing lifecycle, if available.
 * <p>
 * A processing lifecycle that is attached to this manager must be in charge of its own transaction. When it starts on
 * a thread that already has an active transaction, and the transaction definition would make it take part in that
 * transaction (or in a nested one), the processing lifecycle fails with an
 * {@link org.springframework.transaction.IllegalTransactionStateException} and the active transaction is left
 * untouched. Starting a new transaction, for example with {@code PROPAGATION_REQUIRES_NEW}, is accepted. Taking part
 * in an active transaction can be allowed with {@link #setAllowJoiningActiveTransaction(boolean)}, but that is not a
 * supported configuration.
 * <p>
 * A processing lifecycle uses one connection: when it begins, on the thread that began the transaction, the connection
 * of the transaction is looked up from the {@link ConnectionProvider}. Components that read on behalf of the processing
 * lifecycle then reuse it, also on other threads (one at a time), instead of taking another connection from the pool,
 * which can deadlock a small pool. This means the {@link ConnectionProvider} is asked for a connection for every
 * processing lifecycle, whether it uses the database or not. When the connection is
 * {@link org.axonframework.common.jdbc.PhysicalConnectionAware}, as those of a
 * {@link org.axonframework.extension.spring.jdbc.TrackingLazyConnectionDataSource} are, reads use other connections
 * for as long as the connection holds no physical connection yet, which allows them to run in parallel.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class SpringTransactionManager implements TransactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringTransactionManager.class);

    private final PlatformTransactionManager transactionManager;
    private final EntityManagerProvider entityManagerProvider;
    private final ConnectionProvider connectionProvider;
    private final TransactionDefinition transactionDefinition;
    private final AtomicBoolean joinedTransactionLogged = new AtomicBoolean();

    private volatile boolean allowJoiningActiveTransaction;

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

    /**
     * Sets whether a processing lifecycle that starts while a transaction is already active is allowed to take part in
     * that transaction. By default this is not allowed, and such a processing lifecycle fails.
     * <p>
     * <b>Allowing this is not a supported configuration.</b> A processing lifecycle that takes part in an active
     * transaction does not own the commit: it reports success while the active transaction can still roll its work
     * back, and the actions it runs after its commit (for example, waking up event streams, finalizing appended events,
     * or updating subscription queries) run before the active transaction is committed. Components that rely on the
     * commit having happened then act too early, which can leave events invisible to, or skipped by, streams.
     *
     * @param allowJoiningActiveTransaction {@code true} to allow taking part in an active transaction, {@code false}
     *                                      (the default) to fail
     */
    public void setAllowJoiningActiveTransaction(boolean allowJoiningActiveTransaction) {
        this.allowJoiningActiveTransaction = allowJoiningActiveTransaction;
    }

    @Override
    public void attachToProcessingLifecycle(ProcessingLifecycle processingLifecycle) {
        processingLifecycle.runOnPreInvocation(pc -> {
            TransactionStatus status = beginTransactionOfUnitOfWork();
            Transaction transaction = transactionOf(status);

            pc.runOnCommit(p -> transaction.commit());
            pc.onError((p, phase, e) -> transaction.rollback());

            if (entityManagerProvider != null) {
                EntityManagerExecutor executor = entityManagerExecutorOfTransaction();

                pc.putResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY, CachingSupplier.of(() -> executor));
            }

            if (connectionProvider != null) {
                ConnectionExecutor executor = connectionExecutorOfTransaction();

                pc.putResource(JdbcTransactionalExecutorProvider.SUPPLIER_KEY, CachingSupplier.of(() -> executor));
            }
        });
    }

    /*
     * The entity manager of the transaction is looked up here, on the thread that began the transaction, as that is
     * where Spring has it bound. Spring's shared entity manager, which is what is commonly provided, looks up the
     * entity manager of the current thread on every call: on another thread it would not take part in the
     * transaction, and would need a connection of its own.
     *
     * Only an entity manager of the entity manager factory of the provider is looked up. When none is bound to the
     * transaction, because it is not a JPA transaction or because it belongs to another entity manager factory, there
     * is nothing to look up, and the provider is asked when the executor is first used.
     */
    private EntityManagerExecutor entityManagerExecutorOfTransaction() {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(
            entityManagerProvider.getEntityManager().getEntityManagerFactory()
        );

        if (entityManager == null) {
            return new EntityManagerExecutor(entityManagerProvider);
        }

        return new EntityManagerExecutor(entityManager);
    }

    /*
     * The connection of the transaction is looked up here, on the thread that began the transaction, as that is where
     * Spring has it bound. Whoever reads later, possibly on another thread, can then reuse it, and never needs to
     * take another connection from the pool while the unit of work holds one. That is what could otherwise deadlock a
     * small pool.
     *
     * When the connection is able to tell whether it holds a physical connection yet, the executor is only active
     * once it does, so readers can use other connections in parallel until then. Otherwise it is assumed to be in use
     * from the start, which is the case for a transaction manager that takes its connection when it begins.
     */

    private ConnectionExecutor connectionExecutorOfTransaction() {
        Connection connection;

        try {
            connection = connectionProvider.getConnection();
        }
        catch (SQLException e) {
            throw new JdbcException("Could not obtain the connection of the transaction", e);
        }

        if (connection instanceof PhysicalConnectionAware) {
            return new PhysicalConnectionAwareExecutor(connection);
        }

        return new ConnectionExecutor(connection);  // active from the start, so readers use this connection
    }

    @Override
    public Transaction startTransaction() {
        return transactionOf(transactionManager.getTransaction(transactionDefinition));
    }

    private Transaction transactionOf(TransactionStatus status) {
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

    /*
     * A unit of work that joins a transaction that was already active is not in charge of its commit: the commit and
     * after commit phases of the unit of work would run before anything is committed. Things that react to the commit,
     * like an event store that finalizes appended events, would then not see the work of the unit of work, and never
     * make up for it.
     */
    private TransactionStatus beginTransactionOfUnitOfWork() {
        TransactionStatus status = transactionManager.getTransaction(transactionDefinition);

        if (!status.isNewTransaction() && TransactionSynchronizationManager.isActualTransactionActive()) {
            if (allowJoiningActiveTransaction) {
                if (joinedTransactionLogged.compareAndSet(false, true)) {
                    LOGGER.warn(
                        "A unit of work takes part in a transaction that was already active, as that was allowed. "
                            + "This is not a supported configuration: its commit does not commit anything, and the "
                            + "actions it runs after its commit run before the active transaction is committed. "
                            + "This is only logged once."
                    );
                }

                return status;
            }

            if (status.hasSavepoint()) {
                transactionManager.rollback(status);  // only releases the savepoint, the outer transaction is not affected
            }

            throw new IllegalTransactionStateException(
                "A unit of work cannot take part in a transaction that was already active, as its commit would not "
                    + "commit anything. Start it outside of that transaction, or use a transaction definition that "
                    + "starts a new transaction, such as PROPAGATION_REQUIRES_NEW."
            );
        }

        return status;
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
