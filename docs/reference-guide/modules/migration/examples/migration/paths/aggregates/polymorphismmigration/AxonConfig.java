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
package migration.paths.aggregates.polymorphismmigration;

// tag::declarative-polymorphic-registration[]
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.entity.PolymorphicEntityMetamodel;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;

public class AxonConfig {
    // ...
    public void configure(EventSourcingConfigurer configurer) {
         configurer.registerEntity(
                EventSourcedEntityModule.declarative(String.class, GiftCard.class)
                        .messagingModel((configuration, builder) -> PolymorphicEntityMetamodel.forSuperType(GiftCard.class)
                                .addConcreteType(AnnotatedEntityMetamodel.forConcreteType(
                                        OpenLoopGiftCard.class,
                                        configuration.getComponent(ParameterResolverFactory.class),
                                        configuration.getComponent(HandlerDefinition.class),
                                        configuration.getComponent(MessageTypeResolver.class),
                                        configuration.getComponent(MessageConverter.class),
                                        configuration.getComponent(EventConverter.class)
                                ))
                                .addConcreteType(AnnotatedEntityMetamodel.forConcreteType(
                                        RechargeableGiftCard.class,
                                        configuration.getComponent(ParameterResolverFactory.class),
                                        configuration.getComponent(HandlerDefinition.class),
                                        configuration.getComponent(MessageTypeResolver.class),
                                        configuration.getComponent(MessageConverter.class),
                                        configuration.getComponent(EventConverter.class)
                                ))
                                .build())
                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(OpenLoopGiftCard::new))
                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("cardId", id)))
                        .build()
        );
    }
}
// end::declarative-polymorphic-registration[]
