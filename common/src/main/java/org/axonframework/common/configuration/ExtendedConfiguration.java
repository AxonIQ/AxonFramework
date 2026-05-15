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
import org.jspecify.annotations.Nullable;

/**
 * A configuration that supports reading modular {@link ConfigurationExtension} instances.
 * <p>
 * Extensions must be registered first via {@link ExtensibleConfigurer#extend(Class, java.util.function.Supplier)}. If
 * no extension of the requested type has been registered, {@code null} is returned.
 * <p>
 * For registering extensions, see {@link ExtensibleConfigurer}.
 *
 * @author Mateusz Nowak
 * @see ExtensibleConfigurer
 * @since 5.1.0
 */
@Internal
public interface ExtendedConfiguration {

    /**
     * Returns the extension of the given type, or {@code null} if no extension of that type has been registered.
     * <p>
     * Returns the instance created by the factory registered via
     * {@link ExtensibleConfigurer#extend(Class, java.util.function.Supplier)}.
     *
     * @param extensionType the extension class
     * @param <T>           the extension type
     * @return the extension instance, or {@code null} if not registered
     */
    @Nullable
    <T extends ConfigurationExtension<?>> T extension(Class<T> extensionType);
}
