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

import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.configuration.reflection.HandlerDefinitionUtils;
import org.axonframework.messaging.core.configuration.reflection.HandlerEnhancerDefinitionUtils;

/**
 * {@link ConfigurationEnhancer} that registers the {@link ClasspathHandlerDefinition} as the default
 * {@link HandlerDefinition}, and the {@link ClasspathHandlerEnhancerDefinition} as the default
 * {@link HandlerEnhancerDefinition}.
 * <p>
 * Any {@code HandlerDefinition} or {@code HandlerEnhancerDefinition} registered separately, for example through
 * {@link ComponentRegistry#registerComponent(Class, org.axonframework.common.configuration.ComponentBuilder)} or an
 * injectable bean, is composed with these classpath-discovered defaults rather than replacing them, through
 * {@link HandlerDefinitionUtils#registerToComponentRegistry(ComponentRegistry, java.util.function.Function)} and
 * {@link HandlerEnhancerDefinitionUtils#registerToComponentRegistry(ComponentRegistry, java.util.function.Function)}.
 * <p>
 * Registered once at the root and not re-invoked in child module registries. The {@code HandlerDefinition} and
 * {@code HandlerEnhancerDefinition} components are resolved through the parent chain, so nested modules reuse the
 * root's classpath-discovered defaults. Re-invoking per nesting level would compose the classpath enhancer set into
 * the resolved {@code HandlerDefinition} once per level (the {@code flatten} in {@code MultiHandlerEnhancerDefinition}
 * does not de-duplicate), inflating the enhancer list and applying every enhancer once per level.
 *
 * @author Steven van Beelen
 * @since 5.3.2
 */
@RegistrationScope("Register the classpath handler definitions once at the root; do not re-invoke in child module "
        + "registries. They are resolved through the parent chain, and re-registering per nesting level would "
        + "duplicate the classpath enhancer set in the composed HandlerDefinition.")
public class ClasspathHandlerDefinitionConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        HandlerDefinitionUtils.registerToComponentRegistry(
                componentRegistry, c -> ClasspathHandlerDefinition.forClass(c.getClass())
        );
        HandlerEnhancerDefinitionUtils.registerToComponentRegistry(
                componentRegistry, c -> ClasspathHandlerEnhancerDefinition.forClass(c.getClass())
        );
    }
}
