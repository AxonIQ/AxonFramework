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
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.LifecycleHandler;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A {@link LifecycleRegistry} implementation that registers all lifecycle handlers as Micronaut Singletons
 * beans to ensure Micronaut weaves these lifecycles into the other
 * Micronaut bean lifecycles.
 * <p>
 * this {@code LifecycleRegistry} is capable of registering the
 * beans based on the {@link LifecycleHandler LifecycleHandlers} provided through
 * {@link #onStart(int, LifecycleHandler)}  and {@link #onShutdown(int, LifecycleHandler)}.
 *
 * @author Allard Buijze
 * @since 5.0.0
 */
@Internal
@Singleton
public class MicronautLifecycleRegistry implements  LifecycleRegistry {

    private final BeanContext beanContext;

    public MicronautLifecycleRegistry(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final TreeMap<Integer, List<LifecycleHandler>> startHandlers = new TreeMap<>();
    private final TreeMap<Integer, List<LifecycleHandler>> shutdownHandlers = new TreeMap<>(Comparator.reverseOrder());


    @Override
    public LifecycleRegistry registerLifecyclePhaseTimeout(long timeout, @Nonnull TimeUnit timeUnit) {
        logger.warn("Registering lifecycle phase timeout on a Micronaut-based LifecycleRegistry is not supported. "
                            + "Please use Micronauts \"Graceful Shutdown\" support instead.");
        return this;
    }

    @EventListener
    public void on(StartupEvent startupEvent) {
    /*    try {
            startLifecycleHandlers.entrySet().stream().
            this.lifecycleHandlerRunTask = this.lifecycleHandler.run(configurationProvider.get());
            this.lifecycleHandlerRunTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            // This is what the join() would throw
            throw new CompletionException(e);
        }*/
    }

    @PreDestroy
    public void on(ShutDownEvent shutDownEvent) {
 /*       try {
            startLifecycleHandlers.entrySet().stream().
            this.lifecycleHandlerRunTask = this.lifecycleHandler.run(configurationProvider.get());
            this.lifecycleHandlerRunTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            // This is what the join() would throw
            throw new CompletionException(e);
        }*/
    }

    @Override
    public LifecycleRegistry onStart(int phase, @Nonnull LifecycleHandler startHandler) {
//        startLifecycleHandlers.put(phase, startHandler);
        beanContext.createBean(MicronautLifecycleStartHandler.class,startHandler);
        return this;
    }

    @Override
    public LifecycleRegistry onShutdown(int phase, @Nonnull LifecycleHandler shutdownHandler) {
        beanContext.createBean(MicronautLifecycleShutdownHandler.class, shutdownHandler);
        return this;
    }
}
