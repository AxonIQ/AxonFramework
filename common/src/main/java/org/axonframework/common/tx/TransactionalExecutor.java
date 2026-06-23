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

package org.axonframework.common.tx;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.function.ThrowingConsumer;
import org.axonframework.common.function.ThrowingFunction;
import org.jspecify.annotations.Nullable;


import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Executes transactional operations with automatic transaction management. The
 * user of this interface will get (limited) access to a transactional resource,
 * like a JDBC connection or an Entity Manager, with which to perform operations.
 * <p>
 * At the appropriate time in the lifecycle of this executor, commit or rollback
 * is called on the transactional resource managed by it. Any (uncaught) exceptions
 * will result in the resource to be rolled back, and any associated lifecycle to
 * be put into an error state.
 * <p>
 * This interface provides convenience methods for both operations that return
 * a result and operations that are purely side-effecting.
 *
 * @param <T> The type of the resource.
 * @author John Hendrikx
 * @since 5.0.2
 */
@Internal
public interface TransactionalExecutor<T> {

    /**
     * Executes a transactional operation that does not return a result.
     * <p>
     * Implementations are responsible for managing the lifecycle of the
     * provided resource, including obtaining, closing, commit and rollback.
     *
     * @param consumer A consumer which accepts the transactional resource of type {@code T};
     *     cannot be {@code null}.
     * @return A {@link CompletableFuture} with a void result, never {@code null}.
     * @throws NullPointerException When {@code consumer} is {@code null}.
     */
    default CompletableFuture<Void> accept(ThrowingConsumer<T, Exception> consumer) {
        Objects.requireNonNull(consumer, "consumer");

        return apply(r -> {
            consumer.accept(r);
            return null;
        });
    }

    /**
     * Executes a transactional operation that returns a result.
     * <p>
     * Implementations are responsible for managing the lifecycle of the
     * provided resource, including obtaining, closing, commit and rollback.
     *
     * @param <R> The type of the result returned by the function.
     * @param function A function that accepts the transactional resource of type {@code T}
     *     and produces a result of type {@code R}; cannot be {@code null}.
     * @return A {@link CompletableFuture} which when it completes contains the result of
     *     the provided function, never {@code null}.
     * @throws NullPointerException When {@code function} is {@code null}.
     */
    <R> CompletableFuture<R> apply(ThrowingFunction<T, R, Exception> function);
}
