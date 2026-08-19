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

package org.axonframework.messaging.core.reflection;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;

/**
 * {@link ConfigurationEnhancer} that registers the {@link ClasspathHandlerDefinition} as the default
 * {@link HandlerDefinition}.
 * <p>
 * Registering the {@code HandlerDefinition} as a component makes it available for
 * {@link ComponentRegistry#registerDecorator(Class, int, org.axonframework.common.configuration.ComponentDecorator)
 * decoration}, allowing other modules to wrap the handlers created for annotated message handling methods. Without a
 * registered component, decorators for the {@code HandlerDefinition} never apply.
 * <p>
 * The default resolves its delegates through the {@link java.util.ServiceLoader} using the class loader of the
 * {@link org.axonframework.common.configuration.Configuration} implementation, which is the class loader that carries
 * the framework itself. This mirrors {@link ClasspathParameterResolverConfigurationEnhancer}, which registers the
 * default {@link org.axonframework.messaging.core.annotation.ParameterResolverFactory} the same way. Applications
 * needing a different class loader, a curated set of definitions, or additional
 * {@link org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition HandlerEnhancerDefinitions} register
 * their own {@code HandlerDefinition} component, which takes precedence over this default.
 *
 * @author Mateusz Nowak
 * @see ClasspathHandlerDefinition
 * @since 5.4.0
 */
public class ClasspathHandlerDefinitionConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        componentRegistry.registerIfNotPresent(
                HandlerDefinition.class,
                configuration -> ClasspathHandlerDefinition.forClass(configuration.getClass())
        );
    }
}
