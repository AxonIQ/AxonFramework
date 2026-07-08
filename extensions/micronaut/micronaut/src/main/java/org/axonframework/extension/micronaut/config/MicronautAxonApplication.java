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

import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;

import java.util.function.Consumer;

/**
 * A singleton {@link ApplicationConfigurer} implementation using the application contexts {@link ComponentRegistry} and
 * {@link LifecycleRegistry}.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Singleton
public class MicronautAxonApplication implements ApplicationConfigurer {

    private final LifecycleRegistry lifecycleRegistry;
    private final ComponentRegistry componentRegistry;
    private final Provider<AxonConfiguration> axonConfigurationProvider;


    /**
     * @param componentRegistry         The {@link ComponentRegistry} used for
     *                                  {@link #componentRegistry(Consumer)} operation.
     * @param lifecycleRegistry         The {@link ComponentRegistry} used for
     *                                  {@link #lifecycleRegistry(Consumer)} operation.
     * @param axonConfigurationProvider For providing the {@link AxonConfiguration} {@link #build() built} needs.
     */
    @Internal
    public MicronautAxonApplication(LifecycleRegistry lifecycleRegistry,
                                    ComponentRegistry componentRegistry,
                                    Provider<AxonConfiguration> axonConfigurationProvider
    ) {
        this.componentRegistry = componentRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
        this.axonConfigurationProvider = axonConfigurationProvider;
    }

    @Override
    public ApplicationConfigurer componentRegistry(Consumer<ComponentRegistry> componentRegistrar) {
        componentRegistrar.accept(componentRegistry);
        return this;
    }

    @Override
    public ApplicationConfigurer lifecycleRegistry(Consumer<LifecycleRegistry> lifecycleRegistrar) {
        lifecycleRegistrar.accept(lifecycleRegistry);
        return this;
    }

    @Override
    public AxonConfiguration build() {
        return axonConfigurationProvider.get();
    }
}