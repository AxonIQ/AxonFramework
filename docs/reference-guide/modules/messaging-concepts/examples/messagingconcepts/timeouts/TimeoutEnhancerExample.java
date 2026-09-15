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
package messagingconcepts.timeouts;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.timeout.TaskTimeoutSettings;
import org.axonframework.messaging.core.timeout.TimeoutUnitOfWorkFactoryConfiguration;

import java.util.Map;

class TimeoutEnhancerExample {

    // tag::timeout-configuration-enhancer[]
    // Spring users can make a Spring bean of the ConfigurationEnhancer to auto inject it into Axon.
    public class TimeoutConfigurationEnhancer implements ConfigurationEnhancer {

        @Override
        public void enhance(ComponentRegistry registry) {
            registry.registerIfNotPresent(
                    TimeoutUnitOfWorkFactoryConfiguration.class,
                    c -> new TimeoutUnitOfWorkFactoryConfiguration(
                            new TaskTimeoutSettings(30000, 25000, 1000), // command bus
                            new TaskTimeoutSettings(30000, 25000, 1000), // query bus
                            new TaskTimeoutSettings(30000, 25000, 1000), // event processors without specific settings
                            Map.of("slow-processor", new TaskTimeoutSettings(60000, 50000, 1000))
                    )
            );
        }
    }

    // end::timeout-configuration-enhancer[]
    // tag::timeout-configuration-enhancer[]
    // Somewhere in your configuration class...
    public void registerTimeoutEnhancer(MessagingConfigurer configurer) {
        configurer.componentRegistry(
                cr -> cr.registerEnhancer(new TimeoutConfigurationEnhancer())
        );
    }
    // end::timeout-configuration-enhancer[]
}
