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

import org.axonframework.common.annotation.Internal;

import java.util.function.Supplier;

/**
 * A configurer that supports registering {@link ConfigurationExtension} instances.
 * <p>
 * Extensions are created eagerly when {@link #extend(Class, Supplier)} is called — the factory is invoked immediately
 * and the result is stored. If {@code extend()} is called multiple times for the same type, the new instance always
 * replaces the previous one.
 * <p>
 * For reading extensions, see {@link ExtendedConfiguration}.
 *
 * @author Mateusz Nowak
 * @see ExtendedConfiguration
 * @since 5.1.0
 */
@Internal
public interface ExtensibleConfigurer {

    /**
     * Registers an extension factory for the given type and returns {@code this} configurer for chaining.
     * <p>
     * The factory is invoked immediately — the extension is created eagerly, not lazily. If called multiple times for
     * the same type, the new instance always replaces the previous one.
     * <p>
     * Example:
     * <pre>{@code
     * config.extend(DeadLetterQueueConfiguration.class, () -> new DeadLetterQueueConfiguration().enabled().factory(myFactory))
     *       .extend(MetricsExtension.class, () -> new MetricsExtension().enabled());
     * }</pre>
     *
     * @param extensionType the extension class
     * @param factory       a supplier that returns a configured extension
     * @param <T>           the extension type
     * @return {@code this} configurer, for fluent chaining
     */
    <T extends ConfigurationExtension<?>> ExtensibleConfigurer extend(Class<T> extensionType, Supplier<T> factory);
}
