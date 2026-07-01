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

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Provider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;

import java.util.concurrent.ExecutionException;

/**
 * A {@link MicronautLifecycleHandler} implementation for {@link LifecycleHandler start-specific lifecycle handler}
 * Integrates with micronaut by being a {@link ApplicationEventListener<StartupEvent> StartupEvent listener} which runs
 * the LifecycleHandler on startup
 * <p>
 * This class is designed to be registered as a singleton to participate in Micronaut's ApplicationContext Lifecycle,
 * therefore it must be annotated with {@link Introspected}
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Introspected public final class MicronautLifecycleStartHandler
        extends MicronautLifecycleHandler implements ApplicationEventListener<StartupEvent> {

    /**
     * @param configurationProvider A provider for {@link Configuration} which is given to the {@param lifecycleHandler}
     *                              as an argument during invocation.
     * @param phase                 The phase to register the Lifecycle Handler in.
     * @param lifecycleHandler      The {@link LifecycleHandler} to invoke.
     */
    @Internal
    public MicronautLifecycleStartHandler(Provider<Configuration> configurationProvider,
                                          int phase,
                                          LifecycleHandler lifecycleHandler) {
        super(configurationProvider, phase, lifecycleHandler);
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        try {
            this.run().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
