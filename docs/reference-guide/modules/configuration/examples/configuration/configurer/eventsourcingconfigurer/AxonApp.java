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
package configuration.configurer.eventsourcingconfigurer;

// tag::eventsourcing-configurer-example[]
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.configuration.EntityMetamodelConfigurationBuilder;

class AxonApp {

    public static void main(String[] args) {
        EntityMetamodelConfigurationBuilder<MyEntity> metamodelBuilder =
                (configuration, builder) -> builder.creationalCommandHandler(
                                                           new QualifiedName("creational-command"),
                                                           (command, context) -> MessageStream.empty().cast()
                                                   )
                                                   // Additional handlers omitted
                                                   .build();
        EventSourcedEntityModule<MyId, MyEntity> myEntityModule =
                EventSourcedEntityModule.declarative(MyId.class, MyEntity.class)
                                        .messagingModel(metamodelBuilder)
                                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(MyEntity::new))
                                        .criteriaResolver(c -> (id, context) ->
                                                EventCriteria.havingTags("myId", "value"))
                                        .build();
        EventSourcingConfigurer.create()
                               .registerEntity(myEntityModule);
    }
}
// end::eventsourcing-configurer-example[]
