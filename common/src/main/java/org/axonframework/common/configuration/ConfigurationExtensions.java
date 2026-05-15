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

package org.axonframework.common.configuration;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal helper that manages the lifecycle of {@link ConfigurationExtension} instances for an
 * {@link ExtensibleConfigurer} parent configuration.
 * <p>
 * Extensions are created eagerly when {@link #extend(Class, Supplier)} is called — the factory is invoked immediately
 * and the resulting instance is stored. If {@code extend()} is called again for the same type, the previous instance is
 * replaced. {@link #extension(Class)} returns the stored instance, or {@code null} if no extension of that type has
 * been registered.
 * <p>
 * This class is not part of the public API. It exists purely to encapsulate extension management so that
 * {@link ExtensibleConfigurer} implementations can delegate to it without duplicating logic.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@Internal
public class ConfigurationExtensions implements DescribableComponent {

    private final ExtensibleConfigurer parentConfigurer;
    private final Map<Class<? extends ConfigurationExtension<?>>, ConfigurationExtension<?>> extensions =
            new LinkedHashMap<>();

    /**
     * Constructs a new {@code ConfigurationExtensions} for the given {@code parentConfigurer}.
     *
     * @param parentConfigurer the parent configuration that owns these extensions
     */
    public ConfigurationExtensions(ExtensibleConfigurer parentConfigurer) {
        Objects.requireNonNull(parentConfigurer, "Parent configuration may not be null");
        this.parentConfigurer = parentConfigurer;
    }

    /**
     * Returns the extension of the given {@code extensionType}, or {@code null} if no extension of that type has been
     * registered via {@link #extend(Class, Supplier)}.
     *
     * @param extensionType the extension class
     * @param <T>           the extension type
     * @return the extension instance, or {@code null} if not registered
     */
    @SuppressWarnings("unchecked")
    public <T extends ConfigurationExtension<?>> @Nullable T extension(Class<T> extensionType) {
        return (T) extensions.get(extensionType);
    }

    /**
     * Registers an extension by invoking the given {@code factory} immediately and storing the result.
     * <p>
     * If an extension of the same type was previously registered, it is replaced — the factory always overrides the
     * previous instance.
     *
     * @param extensionType the extension class
     * @param factory       a supplier that returns a configured extension
     * @param <T>           the extension type
     * @return the parent configurer, for fluent chaining
     */
    public <T extends ConfigurationExtension<?>> ExtensibleConfigurer extend(
            Class<T> extensionType,
            Supplier<T> factory
    ) {
        extensions.put(extensionType, factory.get());
        return parentConfigurer;
    }

    /**
     * Validates all registered extensions by calling {@link ConfigurationExtension#validate()} on each.
     *
     * @throws AxonConfigurationException if any extension's validation fails
     */
    public void validate() {
        extensions.values().forEach(ConfigurationExtension::validate);
    }

    /**
     * Copies extensions from the given {@code source} that are not already present in {@code this}. Existing extensions
     * on {@code this} are preserved.
     *
     * @param source The source to copy missing extensions from.
     */
    public void extendFrom(ConfigurationExtensions source) {
        source.extensions.forEach(extensions::putIfAbsent);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        extensions.values().forEach(extension -> descriptor.describeProperty(extension.name(), extension));
    }
}
