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

package org.axonframework.messaging.core.unitofwork.transaction.jpa;

import jakarta.persistence.EntityTransaction;
import org.axonframework.common.jpa.EntityManagerExecutor;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.conversion.CachingSupplier;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link TransactionManager} implementation that manages JPA {@link EntityTransaction EntityTransactions} directly
 * via an {@link EntityManagerProvider}.
 * <p>
 * This implementation registers an {@link EntityManagerExecutor} supplier into the
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} using
 * {@link JpaTransactionalExecutorProvider#SUPPLIER_KEY}, making the entity manager available to components that consume
 * it (such as {@link JpaTransactionalExecutorProvider}).
 * <p>
 * A processing lifecycle that is attached to this manager must be in charge of its own transaction: when it starts
 * while a transaction is already active on the entity manager, it fails with an {@link IllegalStateException} and the
 * active transaction is left untouched. Taking part in an active transaction can be allowed with
 * {@link #setAllowJoiningActiveTransaction(boolean)}, but that is not a supported configuration.
 * {@link #startTransaction()} on its own follows a {@code PROPAGATION_REQUIRED} semantic: an active transaction is
 * joined without taking ownership of its lifecycle.
 * <p>
 * Note that JPA {@link jakarta.persistence.EntityManager} instances are not thread-safe and must be accessed from the
 * thread that initiated the transaction. As a result, {@link #requiresSameThreadInvocations()} returns {@code true}.
 * <p>
 * Example usage:
 * <pre>{@code
 * EntityManagerProvider provider = () -> entityManager;
 * TransactionManager txManager = new EntityManagerTransactionManager(provider);
 * txManager.attachToProcessingLifecycle(processingLifecycle);
 * }</pre>
 *
 * @author John Hendrikx
 * @author Mateusz Nowak
 * @since 5.1.0
 */
public class EntityManagerTransactionManager implements TransactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityManagerTransactionManager.class);

    private final EntityManagerProvider entityManagerProvider;
    private final AtomicBoolean joinedTransactionLogged = new AtomicBoolean();

    private volatile boolean allowJoiningActiveTransaction;

    /**
     * Constructs a new {@code EntityManagerTransactionManager}.
     *
     * @param entityManagerProvider the provider of the {@link jakarta.persistence.EntityManager} to use, cannot be
     *                              {@code null}
     * @throws NullPointerException if {@code entityManagerProvider} is {@code null}
     */
    public EntityManagerTransactionManager(EntityManagerProvider entityManagerProvider) {
        this.entityManagerProvider = Objects.requireNonNull(entityManagerProvider, "entityManagerProvider");
    }

    /**
     * Sets whether a processing lifecycle that starts while a transaction is already active on the entity manager is
     * allowed to take part in that transaction. By default this is not allowed, and such a processing lifecycle fails.
     * <p>
     * <b>Allowing this is not a supported configuration.</b> A processing lifecycle that takes part in an active
     * transaction does not own the commit: it reports success while the active transaction can still roll its work
     * back, and the actions it runs after its commit (for example, waking up event streams, or updating subscription
     * queries) run before the active transaction is committed. Components that rely on the commit having happened
     * then act too early, which can leave events invisible to, or skipped by, streams.
     *
     * @param allowJoiningActiveTransaction {@code true} to allow taking part in an active transaction, {@code false}
     *                                      (the default) to fail
     */
    public void setAllowJoiningActiveTransaction(boolean allowJoiningActiveTransaction) {
        this.allowJoiningActiveTransaction = allowJoiningActiveTransaction;
    }

    @Override
    public Transaction startTransaction() {
        EntityTransaction tx = entityManagerProvider.getEntityManager().getTransaction();
        if (tx.isActive()) {
            return joinedTransaction();
        }
        tx.begin();
        return ownedTransaction(tx);
    }

    /*
     * The unit of work took part in a transaction that was already active, so whoever started that transaction decides
     * over its commit and rollback. Committing or rolling it back here would end a transaction that this unit of work
     * did not start, hence both do nothing.
     */
    private Transaction joinedTransaction() {
        return new Transaction() {
            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }
        };
    }

    private Transaction ownedTransaction(EntityTransaction tx) {
        return new Transaction() {
            @Override
            public void commit() {
                if (tx.isActive()) {
                    if (tx.getRollbackOnly()) {
                        tx.rollback();
                    } else {
                        tx.commit();
                    }
                }
            }

            @Override
            public void rollback() {
                if (tx.isActive()) {
                    tx.rollback();
                }
            }
        };
    }

    /*
     * A unit of work that joins a transaction that was already active is not in charge of its commit: the commit and
     * after commit phases of the unit of work would run before anything is committed. Things that react to the commit,
     * like waking up event streams for appended events, would then do so before the events can be seen.
     */
    private Transaction beginTransactionOfUnitOfWork() {
        EntityTransaction tx = entityManagerProvider.getEntityManager().getTransaction();

        if (tx.isActive()) {
            if (!allowJoiningActiveTransaction) {
                throw new IllegalStateException(
                    "A unit of work cannot take part in a transaction that was already active, as its commit would "
                        + "not commit anything. Start it outside of that transaction."
                );
            }

            if (joinedTransactionLogged.compareAndSet(false, true)) {
                LOGGER.warn(
                    "A unit of work takes part in a transaction that was already active, as that was allowed. This is "
                        + "not a supported configuration: its commit does not commit anything, and the actions it "
                        + "runs after its commit run before the active transaction is committed. This is only "
                        + "logged once."
                );
            }

            return joinedTransaction();
        }

        tx.begin();

        return ownedTransaction(tx);
    }

    @Override
    public void attachToProcessingLifecycle(ProcessingLifecycle processingLifecycle) {
        processingLifecycle.runOnPreInvocation(pc -> {
            Transaction transaction = beginTransactionOfUnitOfWork();
            AtomicBoolean concluded = new AtomicBoolean(false);

            pc.putResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY, CachingSupplier.of(() -> new EntityManagerExecutor(entityManagerProvider)));
            pc.runOnCommit(p -> {
                if (concluded.compareAndSet(false, true)) {
                    transaction.commit();
                }
            });
            pc.onError((p, phase, e) -> {
                if (concluded.compareAndSet(false, true)) {
                    transaction.rollback();
                }
            });
        });
    }

    @Override
    public boolean requiresSameThreadInvocations() {
        return true;
    }
}
