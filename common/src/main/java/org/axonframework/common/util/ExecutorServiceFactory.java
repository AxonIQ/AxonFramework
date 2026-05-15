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

package org.axonframework.common.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

/**
 * A Functional Interface towards a {@link BiFunction} which ingests both a Configuration and a {@link BlockingQueue} of
 * {@link Runnable}, and outputs an {@link ExecutorService}. Provides a means to allow configuration of the used
 * {@code ExecutorService} in, for example, the {@code DistributedCommandBus}, but maintaining the option for the
 * framework to provide a {@code BlockingQueue} which is tailored towards message prioritization when building the
 * executor.
 * <p>
 * Before 5.0.0 this class was specific for the Axon Server configuration, but it has been generalized to allow other
 * configurations to provide their own {@code ExecutorService} implementations as well.
 *
 * @param <C> The type of configuration to use for constructing a {@link ExecutorService}.
 * @author Steven van Beelen
 * @since 5.0.0
 */
@FunctionalInterface
public interface ExecutorServiceFactory<C> {

    /**
     * Creates an {@link ExecutorService} based on the given {@code configuration} and {@code queue}.
     *
     * @param configuration The Configuration to use for the ExecutorService.
     * @param queue         The {@link BlockingQueue} to use for the ExecutorService.
     * @return An {@link ExecutorService}based on the given {@code configuration} and {@code queue}.
     */
    ExecutorService createExecutorService(C configuration, BlockingQueue<Runnable> queue);
}