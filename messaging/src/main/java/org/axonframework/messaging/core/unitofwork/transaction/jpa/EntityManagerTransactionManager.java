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
 * Transaction propagation follows a {@code PROPAGATION_REQUIRED} semantic: if a transaction is already active on the
 * entity manager, the existing transaction is joined without taking ownership of its lifecycle.
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

    private final EntityManagerProvider entityManagerProvider;

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

    @Override
    public Transaction startTransaction() {
        EntityTransaction tx = entityManagerProvider.getEntityManager().getTransaction();
        if (tx.isActive()) {
            return new Transaction() {
                @Override
                public void commit() {
                }

                @Override
                public void rollback() {
                }
            };
        }
        tx.begin();
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

    @Override
    public void attachToProcessingLifecycle(ProcessingLifecycle processingLifecycle) {
        processingLifecycle.runOnPreInvocation(pc -> {
            Transaction transaction = startTransaction();
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
