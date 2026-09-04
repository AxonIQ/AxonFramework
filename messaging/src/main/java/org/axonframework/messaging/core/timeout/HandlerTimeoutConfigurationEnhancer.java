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
package org.axonframework.messaging.core.timeout;

import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.configuration.reflection.HandlerEnhancerDefinitionUtils;

/**
 * A {@link ConfigurationEnhancer} that registers a {@link HandlerTimeoutHandlerEnhancerDefinition}, driven by the
 * {@link HandlerTimeoutConfiguration} present in the {@link org.axonframework.common.configuration.Configuration}.
 * <p>
 * Automatically registers a {@link HandlerTimeoutConfiguration#DEFAULT} {@code HandlerTimeoutConfiguration} when none
 * is present yet.
 *
 * @author Steven van Beelen
 * @see HandlerTimeoutConfiguration
 * @see HandlerTimeoutHandlerEnhancerDefinition
 * @since 5.4.0
 */
@RegistrationScope(
        "Register the decorator once at the root; do not re-invoke in child module registries. The "
                + "DecoratorDefinition is copied down and reaches module-built components on its own. Re-invoking per "
                + "nesting level would register the decorator again, wrapping every handler with a timeout twice."
)
public class HandlerTimeoutConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(HandlerTimeoutConfiguration.class, c -> HandlerTimeoutConfiguration.DEFAULT);
        HandlerEnhancerDefinitionUtils.registerToComponentRegistry(
                registry,
                c -> new HandlerTimeoutHandlerEnhancerDefinition(c.getComponent(HandlerTimeoutConfiguration.class))
        );
    }
}
