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
package commands.commanddispatchers;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.messaging.commandhandling.MetadataRoutingStrategy;
import org.axonframework.messaging.commandhandling.RoutingStrategy;

class RoutingConfigurationExample {

    // tag::custom-routing-strategy-config[]
    // Using Configuration API
    public ApplicationConfigurer configureRouting(ApplicationConfigurer configurer) {
        return configurer.componentRegistry(registry ->
            registry.registerComponent(
                RoutingStrategy.class,
                config -> new MetadataRoutingStrategy("tenantId") // <1>
            )
        );
    }
    // end::custom-routing-strategy-config[]
}
