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

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.graceful.GracefulShutdownConfiguration;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.TimeUnit;

import static io.micronaut.core.util.StringUtils.FALSE;

/**
 * A {@link LifecycleRegistry} implementation that registers all lifecycle handlers as {@link MicronautLifecycleHandler}
 * Singletons
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Requires(missingBeans = LifecycleRegistry.class)
@Singleton
public class MicronautLifecycleRegistry implements LifecycleRegistry {

    private final BeanContext beanContext;
    private final boolean gracefulShutdownEnabled;

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final Provider<Configuration> configurationProvider;

    /**
     * @param beanContext             The {@link BeanContext} used for registering the {@link LifecycleHandler}'s.
     * @param gracefulShutdownEnabled The state of {@link io.micronaut.runtime.graceful.GracefulShutdownConfiguration}
     *                                which sets whether registered {@link MicronautLifecycleShutdownHandler}'s support
     *                                graceful shutdown.
     * @param configurationProvider   The {@link Configuration} provider that is used by the {@link LifecycleHandler}'s
     *                                on invocation.
     */
    @Internal
    public MicronautLifecycleRegistry(BeanContext beanContext,
                                      @Property(name = GracefulShutdownConfiguration.ENABLED, defaultValue = FALSE) boolean gracefulShutdownEnabled,
                                      Provider<Configuration> configurationProvider
    ) {
        this.beanContext = beanContext;
        this.gracefulShutdownEnabled = gracefulShutdownEnabled;
        this.configurationProvider = configurationProvider;
    }

    @Override
    public LifecycleRegistry registerLifecyclePhaseTimeout(long timeout, TimeUnit timeUnit) {
        logger.warn("Registering lifecycle phase timeout on a Micronaut-based LifecycleRegistry is not supported.");
        return this;
    }


    @Override
    public LifecycleRegistry onStart(int phase, LifecycleHandler startHandler) {
        beanContext.registerSingleton(
                MicronautLifecycleStartHandler.class,
                new MicronautLifecycleStartHandler(configurationProvider, phase, startHandler)
        );
        return this;
    }

    @Override
    public LifecycleRegistry onShutdown(int phase, LifecycleHandler shutdownHandler) {
        beanContext.registerSingleton(
                MicronautLifecycleShutdownHandler.class,
                new MicronautLifecycleShutdownHandler(
                        configurationProvider,
                        gracefulShutdownEnabled,
                        phase,
                        shutdownHandler
                )
        );
        return this;
    }
}
