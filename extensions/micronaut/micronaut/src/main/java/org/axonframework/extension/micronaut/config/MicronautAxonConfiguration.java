package org.axonframework.extension.micronaut.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.axonframework.common.TypeReference;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.infra.ComponentDescriptor;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * An {@link AxonConfiguration} implementation which uses the built
 * {@link org.axonframework.extension.micronaut.config.MicronautComponentRegistry.MicronautConfiguration} as a
 * delegate.
 * <p>
 * This Singleton is marked as {@link Primary} so that when a {@link Configuration} is injected anywhere, this candidate
 * will be injected instead of
 * {@link org.axonframework.extension.micronaut.config.MicronautComponentRegistry.MicronautConfiguration} which is also
 * a supertype of {@link Configuration}
 */
@Primary
@Context
@Requires(bean = MicronautComponentRegistry.MicronautConfiguration.class)
class MicronautAxonConfiguration implements AxonConfiguration {

    private final Configuration delegate;

    @Internal
    public MicronautAxonConfiguration(MicronautComponentRegistry.MicronautConfiguration delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start() {
        // ignore, connected to Micronaut lifecycle
    }

    @Override
    public void shutdown() {
        // ignore, connected to Micronaut Lifecycle
    }

    @Override
    public <C> Optional<C> getOptionalComponent(Class<C> type, @Nullable String name) {
        return delegate.getOptionalComponent(type, name);
    }

    @Override
    public <C> Optional<C> getOptionalComponent(TypeReference<C> typeReference,
                                                @Nullable String name) {
        return delegate.getOptionalComponent(typeReference, name);
    }

    @Override
    public <C> C getComponent(Class<C> type,
                              @Nullable String name,
                              Supplier<C> defaultImpl) {
        return delegate.getComponent(type, name, defaultImpl);
    }

    @Override
    public List<Configuration> getModuleConfigurations() {
        return delegate.getModuleConfigurations();
    }

    @Override
    public Optional<Configuration> getModuleConfiguration(String name) {
        return delegate.getModuleConfiguration(name);
    }

    @Nullable
    @Override
    public Configuration getParent() {
        return delegate.getParent();
    }

    @Override
    public <C> Map<String, C> getComponents(Class<C> type) {
        return delegate.getComponents(type);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("configuration", delegate);
    }
}
