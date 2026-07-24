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
package migration.paths.configuration.escapehatches;

// tag::configurer-escape-hatches[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

        // Access the modelling layer
        configurer.modelling(modellingConfigurer -> modellingConfigurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, MyEntity.class)
        ));

        // Access the messaging layer
        configurer.messaging(messagingConfigurer -> messagingConfigurer.registerCommandBus(
                config -> new SimpleCommandBus(config.getComponent(UnitOfWorkFactory.class))
        ));
    }
}
// end::configurer-escape-hatches[]

@org.axonframework.eventsourcing.annotation.EventSourcedEntity
class MyEntity {
}
