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
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.Objects;

/**
 * TransactionManager implementation that uses a {@link org.springframework.transaction.PlatformTransactionManager} as
 * underlying transaction manager. This implementation provides a {@link ConnectionExecutor} and/or
 * {@link EntityManagerExecutor} when attached to a processing lifecycle, if available.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class SpringTransactionManager implements TransactionManager {

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
                pc.putResource(
                    JdbcTransactionalExecutorProvider.SUPPLIER_KEY,
                    CachingSupplier.of(() -> new ConnectionExecutor(connectionProvider))
                );
            }

            pc.runOnCommit(p -> transaction.commit());
            pc.onError((p, phase, e) -> transaction.rollback());
        });
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
