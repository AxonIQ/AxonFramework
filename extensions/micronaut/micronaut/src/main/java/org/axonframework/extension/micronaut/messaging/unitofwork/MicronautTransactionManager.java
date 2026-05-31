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

import io.micronaut.context.annotation.Requires;
import io.micronaut.transaction.SynchronousTransactionManager;
import io.micronaut.transaction.TransactionDefinition;
import io.micronaut.transaction.TransactionStatus;
import io.micronaut.transaction.support.DefaultTransactionDefinition;
import jakarta.annotation.Nonnull;
import jakarta.inject.Singleton;
import org.axonframework.common.Assert;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;

/**
 * TransactionManager implementation that uses a {@link SynchronousTransactionManager<T>} as underlying transaction
 * li
 * manager.
 *
 * @param <T> the resource type
 * @author Daniel Karaishchenko
 * @since 5.1.0
 */
@Singleton
@Requires(beans = {SynchronousTransactionManager.class})
public class MicronautTransactionManager<T> implements TransactionManager {

    private final SynchronousTransactionManager<T> transactionManager;
    private final TransactionDefinition transactionDefinition;

    /**
     * @param transactionManager    The transaction manager to use
     * @param transactionDefinition The definition for transactions to create
     */
    public MicronautTransactionManager(SynchronousTransactionManager<T> transactionManager,
                                       TransactionDefinition transactionDefinition) {
        Assert.notNull(transactionManager, () -> "transactionManager may not be null");
        this.transactionManager = transactionManager;
        this.transactionDefinition = transactionDefinition;
    }

    /**
     * Initializes the SpringTransactionManager with the given {@code transactionManager} and the default transaction
     * definition.
     *
     * @param transactionManager the transaction manager to use
     */
    public MicronautTransactionManager(SynchronousTransactionManager<T> transactionManager) {
        this(transactionManager, new DefaultTransactionDefinition());
    }

    @Nonnull
    @Override
    public Transaction startTransaction() {
        TransactionStatus<T> status = transactionManager.getTransaction(transactionDefinition);
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
    protected void commitTransaction(TransactionStatus<T> status) {
        if (status.isNewTransaction() && !status.isCompleted()) {
            transactionManager.commit(status);
        }
    }

    /**
     * Rolls back the transaction with given {@code status} if the transaction is new and not completed.
     *
     * @param status The status of the transaction to roll back
     */
    protected void rollbackTransaction(TransactionStatus<T> status) {
        if (status.isNewTransaction() && !status.isCompleted()) {
            transactionManager.rollback(status);
        }
    }
}
