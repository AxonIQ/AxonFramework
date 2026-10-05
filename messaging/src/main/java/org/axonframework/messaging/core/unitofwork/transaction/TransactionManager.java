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

package org.axonframework.messaging.core.unitofwork.transaction;

import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Interface towards a mechanism that manages transactions
 * <p/>
 * Typically, this will involve opening database transactions or connecting to external systems.
 *
 * @author Allard Buijze
 * @since 2.0.0
 */
public interface TransactionManager {

    /**
     * Starts a transaction.
     * <p>
     * The return value is the started transaction that can be committed or rolled back.
     *
     * @return the object representing the transaction
     */
    Transaction startTransaction();

    /**
     * Executes the given {@code task} in a new {@link Transaction}.
     * <p>
     * The transaction is committed when the task completes normally, and rolled back when it throws an exception.
     *
     * @param task the task to execute
     */
    default void executeInTransaction(Runnable task) {
        Transaction transaction = startTransaction();
        try {
            task.run();
            transaction.commit();
        } catch (Throwable e) {
            transaction.rollback();
            throw e;
        }
    }

    /**
     * Attaches a {@link Transaction} to the given {@code processingLifecycle} in the
     * {@link ProcessingLifecycle#runOnPreInvocation(Consumer) pre-invocation phase}.
     * <p>
     * The attached {@code Transaction} will from there {@link Transaction#commit() commit} in the
     * {@link ProcessingLifecycle#runOnCommit(Consumer) commit phase} and {@link Transaction#rollback() rollback}
     * {@link ProcessingLifecycle#onError(ProcessingLifecycle.ErrorHandler) on error}.
     * <p>
     * The transaction is concluded (committed or rolled back) exactly once. This guards against a sibling handler
     * registered in the same commit phase, or a handler in a later phase, failing after this transaction has
     * already committed -- which would otherwise also invoke the error handler and roll back an already-committed
     * transaction.
     *
     * @param processingLifecycle the {@code ProcessingLifecycle} to attach a {@link Transaction} to
     */
    default void attachToProcessingLifecycle(ProcessingLifecycle processingLifecycle) {
        processingLifecycle.runOnPreInvocation(pc -> {
            Transaction transaction = startTransaction();
            AtomicBoolean concluded = new AtomicBoolean(false);

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

    /**
     * Invokes the given {@code supplier} in a transaction managed by the current TransactionManager. Upon completion of
     * the call, the transaction will be committed in the case of a regular return value, or rolled back in case an
     * exception occurred.
     * <p>
     * This method is an alternative to {@link #executeInTransaction(Runnable)} in cases where a result needs to be
     * returned from the code to be executed transactionally.
     *
     * @param supplier the supplier of the value to return
     * @param <T>      the type of value to return
     * @return the value returned by the supplier
     */
    default <T> T fetchInTransaction(Supplier<T> supplier) {
        Transaction transaction = startTransaction();
        try {
            T result = supplier.get();
            transaction.commit();
            return result;
        } catch (Throwable e) {
            transaction.rollback();
            throw e;
        }
    }

    /**
     * Indicates whether the tasks contained with the managed transactions must occur on the same thread.
     * <p>
     * By default, this method returns {@code false}, meaning there are no thread affinity requirements for the
     * transactions. Implementations can override this method if there is a specific need for tasks to be invoked on the
     * same thread.
     *
     * @return {@code true} if the handler invocations must occur on the same thread; {@code false} otherwise
     */
    default boolean requiresSameThreadInvocations() {
        return false;
    }
}
