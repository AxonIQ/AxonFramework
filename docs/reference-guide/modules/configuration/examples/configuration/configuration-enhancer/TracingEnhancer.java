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
package configuration.enhancer;

// tag::conditional-enhancement-example[]
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.tracing.SpanFactory;

class TracingEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        if (registry.hasComponent(CommandBus.class)) {
            registry.registerDecorator(
                    CommandBus.class,
                    100,
                    (config, name, delegate) -> new TracingCommandBusDecorator(
                            delegate, config.getComponent(SpanFactory.class)
                    )
            );
        }
        if (registry.hasComponent(QueryBus.class)) {
            registry.registerDecorator(
                    QueryBus.class,
                    100,
                    (config, name, delegate) -> new TracingQueryBusDecorator(
                            delegate, config.getComponent(SpanFactory.class)
                    )
            );
        }
    }
}
// end::conditional-enhancement-example[]
