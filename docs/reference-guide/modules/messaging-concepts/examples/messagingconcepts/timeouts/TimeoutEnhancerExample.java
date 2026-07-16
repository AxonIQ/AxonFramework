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
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutInterceptorBuilder;

class TimeoutEnhancerExample {

    // tag::timeout-configuration-enhancer[]
    // Spring users can make a Spring bean of the ConfigurationEnhancer to auto inject it into Axon.
    public class TimeoutConfigurationEnhancer implements ConfigurationEnhancer {

        @Override
        public void enhance(ComponentRegistry registry) {
            // Register decorators for handler interceptor registry
            registry.registerDecorator(HandlerInterceptorRegistry.class, 0, (config, name, delegate) ->
                    delegate.registerCommandInterceptor(
                                    c -> new UnitOfWorkTimeoutInterceptorBuilder(
                                            "CommandBus", 30000, 25000, 1000
                                    ).buildCommandInterceptor()
                            )
                            .registerQueryInterceptor(
                                    c -> new UnitOfWorkTimeoutInterceptorBuilder(
                                            "Query", 30000, 25000, 1000
                                    ).buildQueryInterceptor()
                            )
                            .registerEventInterceptor(
                                    c -> new UnitOfWorkTimeoutInterceptorBuilder(
                                            "Event", 30000, 25000, 1000
                                    ).buildEventInterceptor()
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
