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
import org.axonframework.common.function.ThrowingFunction;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Lazily obtains a resource from the given {@code resourceSupplier} on first use, caches it, and reuses it
 * for every subsequent {@link #apply(ThrowingFunction)} call.
 * <p>
 * All calls are serialized against each other, since the cached resource is generally not safe for
 * concurrent use. {@link #withMaterializationShielded(Function)} additionally guards materialization
 * against a concurrent caller preferring an independent resource while this one isn't active yet.
 *
 * @param <T> the type of the resource
 * @author John Hendrikx
 * @since 5.4.0
 */
@Internal
public class MaterializingResource<T> {
    private final Callable<T> resourceSupplier;
    private final ReentrantReadWriteLock materializationGuard = new ReentrantReadWriteLock();
    private final Lock useLock = new ReentrantLock();

    private volatile T resource;

    /**
     * Constructs a new instance.
     *
     * @param resourceSupplier supplies the resource to use, invoked at most once, cannot be {@code null}
     * @throws NullPointerException if {@code resourceSupplier} is {@code null}
     */
    public MaterializingResource(Callable<T> resourceSupplier) {
        this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
    }

    /**
     * Runs {@code decision}, passing whether this holder already has a resource materialized, while
     * preventing a concurrent {@link #apply} call from materializing it for the duration - so a caller can
     * fall back to an independent executor without racing a concurrent materialization.
     *
     * @param <R>      the type of the result produced by {@code decision}
     * @param decision given {@code true} if this holder already has a resource materialized, otherwise
     *                 {@code false}; run shielded from a concurrent materialization, cannot be {@code null}
     * @return the result of {@code decision}
     * @throws NullPointerException when {@code decision} is {@code null}
     */
    public <R> R withMaterializationShielded(Function<Boolean, R> decision) {
        Objects.requireNonNull(decision, "decision");

        materializationGuard.readLock().lock();

        try {
            return decision.apply(resource != null);
        }
        finally {
            materializationGuard.readLock().unlock();
        }
    }

    /**
     * Executes a transactional operation that returns a result, materializing the resource first if not
     * already done.
     *
     * @param <R>      the type of the result returned by the function
     * @param function a function that accepts the resource and produces a result of type {@code R}, cannot
     *                 be {@code null}
     * @return a {@link CompletableFuture} which when it completes contains the result of {@code function},
     *         never {@code null}
     * @throws NullPointerException when {@code function} is {@code null}
     */
    public <R> CompletableFuture<R> apply(ThrowingFunction<T, R, Exception> function) {
        Objects.requireNonNull(function, "function");

        try {
            ensureMaterialized();
        }
        catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        useLock.lock();

        try {
            return CompletableFuture.completedFuture(function.apply(resource));
        }
        catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        finally {
            useLock.unlock();
        }
    }

    /**
     * Materializes {@link #resource} if not already done, blocking until no concurrent
     * {@link #withMaterializationShielded(Function)} call is in progress.
     * <p>
     * Only takes the write lock when {@link #resource} is still {@code null}. It never regresses, so a
     * caller already holding the read lock can safely reuse an already-active resource without this method
     * attempting (and deadlocking on) a read-to-write upgrade.
     *
     * @throws Exception when {@link #resourceSupplier} failed to supply a resource
     */
    private void ensureMaterialized() throws Exception {
        if (resource != null) {
            return;
        }

        materializationGuard.writeLock().lock();

        try {
            if (resource == null) {
                resource = resourceSupplier.call();
            }
        }
        finally {
            materializationGuard.writeLock().unlock();
        }
    }
}
