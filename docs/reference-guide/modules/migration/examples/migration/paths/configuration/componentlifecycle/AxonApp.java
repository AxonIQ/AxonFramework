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
package migration.paths.configuration.componentlifecycle;

// tag::component-lifecycle[]
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

        configurer.componentRegistry(cr -> cr.registerComponent(
                ComponentDefinition.ofType(MyComponent.class)
                                   .withBuilder(config -> new MyComponent())
                                   .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, component -> {})
                                   .onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, component -> {})
        ));
    }
}
// end::component-lifecycle[]

class MyComponent {
}
