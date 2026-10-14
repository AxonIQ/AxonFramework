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
package configuration.configurer.accessinglowerlevel;

// tag::accessing-lower-level-example[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer.create()
         /* top level: */      .registerEventStorageEngine(
                                       c -> new InMemoryEventStorageEngine()
                               )
         /* modelling level: */.modelling(mc -> mc.registerEntity(
                                       EventSourcedEntityModule.autodetected(
                                               MyId.class, MyEntity.class
                                       )
                               ))
         /* messaging level: */.messaging(mc -> mc.registerCommandBus(
                                       config -> new SimpleCommandBus(config.getComponent(UnitOfWorkFactory.class))
                               ))
         /* lowest level: */   .componentRegistry(cr -> cr.registerComponent(
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
// end::accessing-lower-level-example[]
