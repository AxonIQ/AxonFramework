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
package configuration.spring;

// tag::customizing-decorator-example[]
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.tracing.SpanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    @Bean
    public DecoratorDefinition<CommandBus, TracingCommandBusDecorator> tracingCommandBusDecorator() {
        return DecoratorDefinition.forType(CommandBus.class)
                                  .with((config, name, delegate) -> new TracingCommandBusDecorator(
                                          delegate, config.getComponent(SpanFactory.class)
                                  ))
                                  .order(10);
    }
}
// end::customizing-decorator-example[]
