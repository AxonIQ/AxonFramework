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

package org.axonframework.extension.micronaut.config;

import io.micronaut.core.order.Ordered;
import jakarta.inject.Provider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;

import java.util.concurrent.CompletableFuture;

/**
 * A wrapper for {@link LifecycleHandler start-specific lifecycle handler} for usage with Micronaut. This class
 * implements {@link Ordered} which corresponds to the {@link LifecycleHandler}'s phase This allows it to be ordered
 * correctly when ingesting a collection of this class.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
public abstract class MicronautLifecycleHandler implements Ordered {

    private final Provider<Configuration> configurationProvider;
    private final LifecycleHandler lifecycleHandler;
    private final int phase;

    /**
     * @param configurationProvider A provider for {@link Configuration} which is given to the {@param lifecycleHandler}
     *                              as an argument during invocation.
     * @param phase                 The phase to register the Lifecycle Handler in.
     * @param lifecycleHandler      The {@link LifecycleHandler} to invoke.
     */
    @Internal
    public MicronautLifecycleHandler(
            Provider<Configuration> configurationProvider,
            int phase,
            LifecycleHandler lifecycleHandler) {
        this.configurationProvider = configurationProvider;
        this.lifecycleHandler = lifecycleHandler;
        this.phase = phase;
    }

    @Override
    public int getOrder() {
        return phase;
    }

    /**
     * Invokes the {@link LifecycleHandler} with the provided {@link Configuration}
     *
     * @return The {@link LifecycleHandler#run} future.
     */
    protected CompletableFuture<?> run() {
        return this.lifecycleHandler.run(configurationProvider.get());
    }
}
