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
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.context.scope.Refreshable;
import io.micronaut.runtime.event.ApplicationShutdownEvent;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.Nonnull;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.lifecycle.LifecycleHandlerInvocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.axonframework.common.FutureUtils.runFailing;

/**
 * A {@link LifecycleRegistry} implementation that registers all lifecycle handlers as Beans and ties them to the
 * application lifecycle beans to ensure Micronaut weaves these lifecycles into the other Micronaut bean lifecycles.
 * <p>
 * Singletons to be registered into the application's lifecycle this {@code LifecycleRegistry} is capable of registering
 * the beans based on the {@link LifecycleHandler LifecycleHandlers} provided through
 * <p>
 * {@link #onStart(int, LifecycleHandler)}  and {@link #onShutdown(int, LifecycleHandler)}. Micronaut does not support
 * lifecycle ordering <a href="https://github.com/micronaut-projects/micronaut-core/issues/6493">yet</a>, so we cannot
 * simply create Ordered
 *
 * @author Daniel Karapishchenko
 * @since 5.0.0
 */
@Internal
@Refreshable
@Singleton
public class MicronautLifecycleRegistry implements LifecycleRegistry {

    private final BeanContext beanContext;


    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final TreeMap<Integer, List<MicronautLifecycleHandler>> startHandlers = new TreeMap<>();
    private final TreeMap<Integer, List<MicronautLifecycleHandler>> shutdownHandlers = new TreeMap<>(Comparator.reverseOrder());

    /**
     * @param beanContext
     */
    public MicronautLifecycleRegistry(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public LifecycleRegistry registerLifecyclePhaseTimeout(long timeout, @Nonnull TimeUnit timeUnit) {
        logger.warn("Registering lifecycle phase timeout on a Micronaut-based LifecycleRegistry is not supported.");
        return this;
    }


    private void invokeStartHandlers() {
        invokeLifecycleHandlers(
                startHandlers,
                (phase, e) -> {
                    String startFailure = String.format(
                            "One of the start handlers in phase [%d] failed with the following exception: ",
                            phase
                    );
                    logger.warn(startFailure, e);
                    throw new LifecycleHandlerInvocationException(startFailure, e);
                }
        );
    }

    private void invokeShutdownHandlers() {
        invokeLifecycleHandlers(
                shutdownHandlers,
                (phase, e) -> {
                    String startFailure = String.format(
                            "One of the shutdown handlers in phase [%d] failed with the following exception: ",
                            phase
                    );
                    logger.warn(startFailure, e);
                }
        );
    }

    private void invokeLifecycleHandlers(TreeMap<Integer, List<MicronautLifecycleHandler>> lifecycleHandlerMap,
                                         BiConsumer<Integer, Exception> exceptionHandler) {
        Integer currentLifecyclePhase;
        Map.Entry<Integer, List<MicronautLifecycleHandler>> phasedHandlers = lifecycleHandlerMap.firstEntry();
        if (phasedHandlers == null) {
            return;
        }

        do {
            currentLifecyclePhase = phasedHandlers.getKey();
            List<MicronautLifecycleHandler> handlers = phasedHandlers.getValue();
            try {
                handlers.stream()
                        .map(lch -> runFailing(lch::run))
                        .map(c -> c.thenRun(() -> {
                        }))
                        .reduce(CompletableFuture::allOf)
                        .orElse(FutureUtils.emptyCompletedFuture())
                        .get();
            } catch (CompletionException | ExecutionException e) {
                exceptionHandler.accept(currentLifecyclePhase, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn(
                        "Completion interrupted during phase [{}]. Proceeding to following phase",
                        currentLifecyclePhase);
            }
        } while ((phasedHandlers = lifecycleHandlerMap.higherEntry(currentLifecyclePhase)) != null);
    }

    @EventListener
    void on(ApplicationStartupEvent startupEvent) {
        invokeStartHandlers();
    }

    @EventListener
    void on(ApplicationShutdownEvent shutdownEvent) {
        invokeShutdownHandlers();
    }

    @Override
    public LifecycleRegistry onStart(int phase, @Nonnull LifecycleHandler startHandler) {
        MicronautLifecycleStartHandler micronautLifecycleStartHandler = beanContext.createBean(
                MicronautLifecycleStartHandler.class,
                phase,
                startHandler);
        startHandlers.computeIfAbsent(phase, p -> new CopyOnWriteArrayList<>())
                     .add(micronautLifecycleStartHandler);
        return this;
    }

    @Override
    public LifecycleRegistry onShutdown(int phase, @Nonnull LifecycleHandler shutdownHandler) {
        MicronautLifecycleShutdownHandler micronautLifecycleShutdownHandler = beanContext.createBean(
                MicronautLifecycleShutdownHandler.class,
                phase,
                shutdownHandler);
        shutdownHandlers.computeIfAbsent(phase, p -> new CopyOnWriteArrayList<>())
                        .add(micronautLifecycleShutdownHandler);
        return this;
    }
}
