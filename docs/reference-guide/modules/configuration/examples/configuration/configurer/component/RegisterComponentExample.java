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
package configuration.configurer.component;

// tag::register-component-example[]
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

class RegisterComponentExample {

    void register() {
        MessagingConfigurer.create()
                           .componentRegistry(registry -> registry.registerComponent(
                                   CommandGateway.class,
                                   config -> new DefaultCommandGateway(
                                           config.getComponent(CommandBus.class),
                                           config.getComponent(MessageTypeResolver.class),
                                           config.getComponent(CommandPriorityCalculator.class),
                                           config.getComponent(RoutingStrategy.class)
                                   )
                           ));
    }
}
// end::register-component-example[]
