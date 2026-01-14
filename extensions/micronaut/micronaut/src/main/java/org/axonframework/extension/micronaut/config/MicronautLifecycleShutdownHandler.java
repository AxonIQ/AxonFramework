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

import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * A Parameterized Singleton implementation wrapping a {@link LifecycleHandler shutdown-specific lifecycle handler} to
 * allow it to be managed by Micronaut.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Internal
//@Singleton
public class MicronautLifecycleShutdownHandler {

    private final Provider<Configuration> configurationProvider;
    private final LifecycleHandler lifecycleHandler;

    /**
     * Initialize the bean to have the given {@code task} executed on start-up in the given {@code phase}.
     *
     */
    MicronautLifecycleShutdownHandler(
            Provider<Configuration> configurationProvider, @Parameter LifecycleHandler lifecycleHandler
    ) {
        this.configurationProvider = configurationProvider;
        this.lifecycleHandler = lifecycleHandler;
    }

    @EventListener
    public void on(ShutdownEvent shutdownEvent) {
        try {
            this.lifecycleHandler.run(configurationProvider.get()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            // This is what the join() would throw
            throw new CompletionException(e);
        }
    }
}
