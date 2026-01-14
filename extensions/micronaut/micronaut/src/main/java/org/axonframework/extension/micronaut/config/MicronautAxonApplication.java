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

import io.micronaut.context.annotation.Bean;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An {@link ApplicationConfigurer} implementation using Micronaut-based {@link ComponentRegistry} and
 * {@link LifecycleRegistry}.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@Internal
@Singleton
public class MicronautAxonApplication implements ApplicationConfigurer {

    private final MicronautComponentRegistry componentRegistry;
    private final MicronautLifecycleRegistry lifecycleRegistry;

    /**
     * Construct a {@code SpringAxonApplicationConfigurer} with the given {@code componentRegistry} and
     * {@code lifecycleRegistry}.
     *
     * @param componentRegistry The Spring-based {@link ComponentRegistry} used for {@link #componentRegistry(Consumer)}
     *                          operation and the {@link AxonConfiguration} {@link #build() built} by this
     *                          {@link ApplicationConfigurer}.
     * @param lifecycleRegistry The Spring-based {@link ComponentRegistry} used for {@link #lifecycleRegistry(Consumer)}
     *                          operation.
     */
    @Internal
    public MicronautAxonApplication(@Nonnull MicronautComponentRegistry componentRegistry,
                                    @Nonnull MicronautLifecycleRegistry lifecycleRegistry) {
        this.componentRegistry = Objects.requireNonNull(componentRegistry, "The componentRegistry may not be null.");
        this.lifecycleRegistry = Objects.requireNonNull(lifecycleRegistry, "The lifecycleRegistry may not be null.");
    }

    @Override
    public ApplicationConfigurer componentRegistry(@Nonnull Consumer<ComponentRegistry> componentRegistrar) {
        componentRegistrar.accept(componentRegistry);
        return this;
    }

    @Override
    public ApplicationConfigurer lifecycleRegistry(@Nonnull Consumer<LifecycleRegistry> lifecycleRegistrar) {
        lifecycleRegistrar.accept(lifecycleRegistry);
        return this;
    }

    @Override
    public AxonConfiguration build() {
        return new AxonConfiguration() {
            @Override
            public void start() {
                // ignore, connected to Spring lifecycle
            }

            @Override
            public void shutdown() {
                // ignore, connected to Spring Lifecycle
            }

            @Nonnull
            @Override
            public <C> C getComponent(@Nonnull Class<C> type) {
                return componentRegistry.configuration().getComponent(type);
            }

            @Nonnull
            @Override
            public <C> C getComponent(@Nonnull Class<C> type, @Nullable String name) {
                return componentRegistry.configuration()
                                        .getComponent(type, name);
            }

            @Override
            public <C> Optional<C> getOptionalComponent(@Nonnull Class<C> type) {
                return componentRegistry.configuration().getOptionalComponent(type);
            }

            @Override
            public <C> Optional<C> getOptionalComponent(@Nonnull Class<C> type, @Nullable String name) {
                return componentRegistry.configuration().getOptionalComponent(type, name);
            }

            @Nonnull
            @Override
            public <C> C getComponent(@Nonnull Class<C> type,
                                      @Nullable String name,
                                      @Nonnull Supplier<C> defaultImpl) {
                return componentRegistry.configuration().getComponent(type, name, defaultImpl);
            }

            @Nonnull
            @Override
            public <C> C getComponent(@Nonnull Class<C> type,
                                      @Nonnull Supplier<C> defaultImpl) {
                return componentRegistry.configuration().getComponent(type, defaultImpl);
            }

            @Override
            public List<Configuration> getModuleConfigurations() {
                return componentRegistry.configuration().getModuleConfigurations();
            }

            @Override
            public Optional<Configuration> getModuleConfiguration(@Nonnull String name) {
                return componentRegistry.configuration().getModuleConfiguration(name);
            }

            @Nullable
            @Override
            public Configuration getParent() {
                return componentRegistry.configuration().getParent();
            }

            @Nonnull
            @Override
            public <C> Map<String, C> getComponents(@Nonnull Class<C> type) {
                return componentRegistry.configuration().getComponents(type);
            }

            @Override
            public void describeTo(@Nonnull ComponentDescriptor descriptor) {
                descriptor.describeProperty("components", componentRegistry);
            }
        };
    }
}
