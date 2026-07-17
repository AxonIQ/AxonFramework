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
 * @author Mateusz Nowak
 * @author John Hendrikx
 * @since 5.0.0
 */
public record UnitOfWorkConfiguration(Executor workScheduler, boolean allowAsyncProcessing, List<Consumer<ProcessingLifecycle>> processingLifecycleEnhancers) {

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
     *
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
        public UnitOfWorkConfiguration forcedSameThreadInvocation() {
        return new UnitOfWorkConfiguration(Runnable::run, false, processingLifecycleEnhancers);
    }

    /**
     * Creates a new configuration with specified work scheduler.
     *
     * @param workScheduler The {@link Executor} for processing actions.
     * @return A new modified {@link UnitOfWorkConfiguration}.
     */
        public UnitOfWorkConfiguration workScheduler(Executor workScheduler) {
        Objects.requireNonNull(workScheduler, "workScheduler may not be null");
        return new UnitOfWorkConfiguration(workScheduler, allowAsyncProcessing, processingLifecycleEnhancers);
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
                Stream.concat(processingLifecycleEnhancers.stream(), Stream.of(enhancer)).toList()
        );
    }
}
