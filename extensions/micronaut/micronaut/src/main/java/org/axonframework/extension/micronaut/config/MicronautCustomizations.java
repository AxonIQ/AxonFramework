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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorModule;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.core.SubscribableEventSource;

import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Holder for Micronaut customizations based on settings.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
interface MicronautCustomizations {

    /**
     * Creates customizations for a pooled streaming event processing module.
     *
     * @param name     Module name.
     * @param settings Settings of the module.
     * @return Customizations for the module.
     */
    static PooledStreamingEventProcessorModule.Customization pooledStreamingCustomizations(
            String name,
            EventProcessorSettings.PooledEventProcessorSettings settings
    ) {
        return new MicronautPooledStreamingEventProcessingModuleCustomization(name, settings);
    }

    /**
     * Creates customizations for a subscribing event processing module.
     *
     * @param name     Module name.
     * @param settings Settings of the module.
     * @return Customizations for the module.
     */
    static SubscribingEventProcessorModule.Customization subscribingCustomizations(
            String name,
            EventProcessorSettings.SubscribingEventProcessorSettings settings) {
        return new MicronautSubscribingEventProcessingModuleCustomization(name, settings);
    }

    /**
     * Customization executed based on the {@link EventProcessorSettings.SubscribingEventProcessorSettings}.
     */
    class MicronautSubscribingEventProcessingModuleCustomization implements SubscribingEventProcessorModule.Customization {

        private final EventProcessorSettings.SubscribingEventProcessorSettings settings;
        private final String name;

        MicronautSubscribingEventProcessingModuleCustomization(
                String name,
                EventProcessorSettings.SubscribingEventProcessorSettings settings) {
            this.name = name;
            this.settings = settings;
        }

        @Override
        public SubscribingEventProcessorConfiguration apply(Configuration configuration,
                                                            SubscribingEventProcessorConfiguration subscribingEventProcessorConfiguration) {
            var messageSource = getComponent(configuration,
                                             SubscribableEventSource.class,
                                             settings.source(),
                                             null
            );
            require(messageSource != null, "Could not find a mandatory Source with name '" + settings.source()
                    + "' for event processor '" + name + "'.");

            //noinspection unchecked
            return subscribingEventProcessorConfiguration
                    .eventSource(messageSource);
        }
    }


    /**
     * Customization executed based on the {@link EventProcessorSettings.PooledEventProcessorSettings}.
     */
    class MicronautPooledStreamingEventProcessingModuleCustomization
            implements PooledStreamingEventProcessorModule.Customization {

        private final EventProcessorSettings.PooledEventProcessorSettings settings;
        private final String name;

        MicronautPooledStreamingEventProcessingModuleCustomization(
                String name,
                EventProcessorSettings.PooledEventProcessorSettings settings
        ) {
            this.settings = settings;
            this.name = name;
        }

        @Override
        public PooledStreamingEventProcessorConfiguration apply(
                Configuration configuration,
                PooledStreamingEventProcessorConfiguration eventProcessorConfiguration) {
            String executorName = "WorkPackage[" + name + "]";
            var scheduledExecutorService = Executors.newScheduledThreadPool(
                    settings.threadCount(),
                    new AxonThreadFactory(executorName)
            );

            var eventStore = getComponent(configuration,
                                          StreamableEventSource.class,
                                          settings.source(),
                                          null);
            require(eventStore != null,
                    "Could not find a mandatory Source with name '" + settings.source()
                            + "' for event processor '" + name + "'.");

            var tokenStore = getComponent(configuration,
                                          TokenStore.class,
                                          settings.tokenStore(),
                                          null);
            require(tokenStore != null,
                    "Could not find a mandatory TokenStore with name '" + settings.tokenStore()
                            + "' for event processor '" + name + "'."
            );
            //noinspection unchecked
            return eventProcessorConfiguration
                    .workerExecutor(scheduledExecutorService)
                    .tokenClaimInterval(settings.tokenClaimIntervalInMillis())
                    .batchSize(settings.batchSize())
                    .initialSegmentCount(settings.initialSegmentCount())
                    .eventSource(eventStore)
                    .tokenStore(tokenStore);
        }
    }

    /**
     * Retrieves component from configuration.
     *
     * @param configuration The configuration holding the component registry.
     * @param type          The type of the component.
     * @param name          An optional component name, if omitted only type is used.
     * @param supplier      An optional supplier, if omitted replaced by the null supplier.
     * @param <T>           type of the component.
     * @return a component of given type and name, if found or supplied by the supplier.
     */
    @Nullable
    static <T> T getComponent(@Nonnull Configuration configuration, @Nonnull Class<T> type,
                              @Nullable String name,
                              @Nullable Supplier<T> supplier) {
        Supplier<T> safeSupplier = (supplier != null) ? supplier : () -> null;
        return configuration.getOptionalComponent(type, name).orElseGet(safeSupplier);
    }

    /**
     * Throws AxonConfiguration exception if the condition is not met.
     *
     * @param condition Condition which has to be met.
     * @param message   Message reported in Axon Configuration Exception, if the condition is not met.
     */
    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AxonConfigurationException(message);
        }
    }
}