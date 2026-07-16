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
package commands.configuration.decoration;

// tag::component-decoration[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.ComponentDecorator;
import org.axonframework.messaging.commandhandling.CommandBus;

public class AxonConfig {

    public void decorateCommandBus(ApplicationConfigurer configurer) {
        configurer.componentRegistry(registry -> registry.registerDecorator(
                CommandBus.class,
                0, // Integer defining the decoration order
                (config, name, commandBus) -> new LoggingCommandBus(commandBus)
        ));
    }
}
// end::component-decoration[]
