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

package org.axonframework.messaging.core.unitofwork;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.axonframework.common.DirectExecutor;
import org.jspecify.annotations.Nullable;

/**
 * Configuration used for the {@link UnitOfWork} creation in the {@link UnitOfWorkFactory}.
 * <p>
 * Defines the work scheduler used during unit of work processing, and allows registering possible enhancers for a unit
 * of work's lifecycle.
 *
 * @param workScheduler                The {@link Executor} for processing unit of work actions.
 * @param allowAsyncProcessing         Whether the unit of work should allow fully asynchronous processing.
 * @param processingLifecycleEnhancers The enhancers that are applied to the processing lifecycle for each created unit
 *                                     of work.
 * @param lifecycleInterceptor         The {@link ProcessingLifecycleInterceptor} invoked immediately around every
 *                                     action and handler dispatch executed by the unit of work, or {@code null} when no
 *                                     interceptor is installed (the direct, zero-overhead path).
 * @author Mateusz Nowak
 * @author John Hendrikx
 * @since 5.0.0
 */
public record UnitOfWorkConfiguration(Executor workScheduler,
                                      boolean allowAsyncProcessing,
                                      List<Consumer<ProcessingLifecycle>> processingLifecycleEnhancers,
                                      @Nullable ProcessingLifecycleInterceptor lifecycleInterceptor) {

    /**
     * Creates a {@link UnitOfWorkConfiguration} without a {@link ProcessingLifecycleInterceptor}. Retained for backward
     * compatibility with callers constructing the configuration before the {@link #lifecycleInterceptor() interceptor}
     * component was introduced.
     *
     * @param workScheduler                the {@link Executor} for processing unit of work actions
     * @param allowAsyncProcessing         whether the unit of work should allow fully asynchronous processing
     * @param processingLifecycleEnhancers the enhancers applied to the processing lifecycle for each created unit of
     *                                     work
     */
    public UnitOfWorkConfiguration(Executor workScheduler,
                                   boolean allowAsyncProcessing,
                                   List<Consumer<ProcessingLifecycle>> processingLifecycleEnhancers) {
        this(workScheduler, allowAsyncProcessing, processingLifecycleEnhancers, null);
    }

    /**
     * Creates default configuration with direct execution.
     *
     * @return Default {@link UnitOfWorkConfiguration} instance.
     */
    static UnitOfWorkConfiguration defaultValues() {
        return new UnitOfWorkConfiguration(DirectExecutor.instance(), true, List.of());
    }

    /**
     * Creates a new {@link UnitOfWorkConfiguration} that forces all handlers to be invoked by the same thread. The
     * configuration uses a direct execution model where all tasks are run immediately on the calling thread, and the
     * coordinating thread will wait for any asynchronous processing to complete.
     * <p>
     * The {@link #processingLifecycleEnhancers() enhancers} and the {@link #lifecycleInterceptor() interceptor} are
     * preserved, so lifecycle customization and state-bridging around framework-executed segments survive the switch to
     * same-thread invocation.
     *
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
    public UnitOfWorkConfiguration forcedSameThreadInvocation() {
        return new UnitOfWorkConfiguration(Runnable::run, false, processingLifecycleEnhancers, lifecycleInterceptor);
    }

    /**
     * Creates a new configuration with specified work scheduler.
     *
     * @param workScheduler The {@link Executor} for processing actions.
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
    public UnitOfWorkConfiguration workScheduler(Executor workScheduler) {
        Objects.requireNonNull(workScheduler, "workScheduler may not be null");
        return new UnitOfWorkConfiguration(workScheduler,
                                           allowAsyncProcessing,
                                           processingLifecycleEnhancers,
                                           lifecycleInterceptor);
    }

    /**
     * Creates a new configuration including the specified enhancer.
     *
     * @param enhancer The processing lifecycle enhancer to include.
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
    public UnitOfWorkConfiguration registerProcessingLifecycleEnhancer(Consumer<ProcessingLifecycle> enhancer) {
        Objects.requireNonNull(enhancer, "enhancer may not be null");

        return new UnitOfWorkConfiguration(
                workScheduler,
                allowAsyncProcessing,
                Stream.concat(processingLifecycleEnhancers.stream(), Stream.of(enhancer)).toList(),
                lifecycleInterceptor
        );
    }

    /**
     * Creates a new configuration that <b>replaces</b> the current {@link #lifecycleInterceptor() interceptor} with the
     * given one. To compose with an already registered interceptor instead of replacing it, use
     * {@link #addLifecycleInterceptor(ProcessingLifecycleInterceptor)}.
     *
     * @param lifecycleInterceptor The {@link ProcessingLifecycleInterceptor} to install.
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
    public UnitOfWorkConfiguration lifecycleInterceptor(ProcessingLifecycleInterceptor lifecycleInterceptor) {
        Objects.requireNonNull(lifecycleInterceptor, "lifecycleInterceptor may not be null");
        return new UnitOfWorkConfiguration(
                workScheduler,
                allowAsyncProcessing,
                processingLifecycleEnhancers,
                lifecycleInterceptor
        );
    }

    /**
     * Creates a new configuration that <b>chains</b> the given interceptor after any already registered
     * {@link #lifecycleInterceptor() interceptor} (composing via
     * {@link ProcessingLifecycleInterceptor#andThen(ProcessingLifecycleInterceptor)}). Chaining (rather than
     * replacement) ensures multiple contributors never clobber each other, which is the common case when several
     * framework modules each install their own interceptor. To replace the current interceptor instead, use
     * {@link #lifecycleInterceptor(ProcessingLifecycleInterceptor)}.
     *
     * @param lifecycleInterceptor The {@link ProcessingLifecycleInterceptor} to chain in.
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
    public UnitOfWorkConfiguration addLifecycleInterceptor(ProcessingLifecycleInterceptor lifecycleInterceptor) {
        Objects.requireNonNull(lifecycleInterceptor, "lifecycleInterceptor may not be null");
        ProcessingLifecycleInterceptor chained = this.lifecycleInterceptor == null
                ? lifecycleInterceptor
                : this.lifecycleInterceptor.andThen(lifecycleInterceptor);
        return new UnitOfWorkConfiguration(
                workScheduler,
                allowAsyncProcessing,
                processingLifecycleEnhancers,
                chained
        );
    }
}
