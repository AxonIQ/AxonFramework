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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.function.ThrowingFunction;
import org.axonframework.common.tx.MaterializationAware;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;

/**
 * Provider of {@link TransactionalExecutor TransactionalExecutors}.
 *
 * @param <T> The type of resource the {@link TransactionalExecutor} works with.
 * @author John Hendrikx
 * @since 5.0.2
 */
@Internal
public interface TransactionalExecutorProvider<T> {

    /**
     * Provides the {@link TransactionalExecutor} bound to {@code processingContext}.
     *
     * @param processingContext a {@link ProcessingContext}, can be {@code null}
     * @return the bound {@link TransactionalExecutor}, or {@code null} if {@code processingContext} is not
     *         {@code null} but has no executor bound to it
     */
    @Nullable TransactionalExecutor<T> findTransactionalExecutor(@Nullable ProcessingContext processingContext);

    /**
     * Provides a {@link TransactionalExecutor}, using the optional processing context.
     *
     * @param processingContext a {@link ProcessingContext}, can be {@code null}
     * @return a {@link TransactionalExecutor}, never {@code null}
     * @throws IllegalStateException when {@code processingContext} is not {@code null} but has no executor bound to it
     */
    default TransactionalExecutor<T> getTransactionalExecutor(@Nullable ProcessingContext processingContext) {
        TransactionalExecutor<T> executor = findTransactionalExecutor(processingContext);

        if (executor == null) {
            throw new IllegalStateException("No transactional executor bound to the given processing context.");
        }

        return executor;
    }

    /**
     * Provides a {@link TransactionalExecutor} that prefers an independently-managed resource over
     * materializing the {@code processingContext}-bound executor's resource if it isn't active yet.
     * <p>
     * The returned executor's {@link TransactionalExecutor#apply} reuses the {@code processingContext}-bound
     * executor's resource if it's already active at the time of that call, otherwise uses an
     * independently-managed resource instead (as if {@code processingContext} were {@code null}), shielded
     * against a concurrent materialization for the duration of that check; see {@link MaterializationAware}.
     * <p>
     * Also falls back to an independently-managed resource when {@code processingContext} has no executor
     * bound to it at all, and uses the context-bound executor unconditionally when it doesn't implement
     * {@link MaterializationAware}.
     *
     * @param processingContext a {@link ProcessingContext}, can be {@code null}
     * @return a {@link TransactionalExecutor}, never {@code null}
     */
    default TransactionalExecutor<T> getTransactionalExecutorPreferringIndependent(@Nullable ProcessingContext processingContext) {
        if (processingContext == null) {
            return getTransactionalExecutor(null);
        }

        return new TransactionalExecutor<>() {
            @Override
            public <R> CompletableFuture<R> apply(ThrowingFunction<T, R, Exception> function) {
                TransactionalExecutor<T> ambient = findTransactionalExecutor(processingContext);

                if (ambient == null) {
                    return getTransactionalExecutor(null).apply(function);
                }

                if (ambient instanceof MaterializationAware materializationAware) {
                    return materializationAware.withMaterializationShielded(active ->
                        active ? ambient.apply(function) : getTransactionalExecutor(null).apply(function)
                    );
                }

                return ambient.apply(function);
            }
        };
    }
}
