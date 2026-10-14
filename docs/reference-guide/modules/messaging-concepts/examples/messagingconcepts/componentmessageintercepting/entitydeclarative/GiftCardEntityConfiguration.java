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
package messagingconcepts.componentmessageintercepting.entitydeclarative;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;

public class GiftCardEntityConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
                EventSourcedEntityModule.declarative(String.class, GiftCard.class)
                                        .messagingModel((config, model) -> {
                                            MessageTypeResolver resolver =
                                                    config.getComponent(MessageTypeResolver.class);
                                            return model
                                                    .instanceCommandHandler(
                                                            resolver.resolveOrThrow(RedeemGiftCard.class)
                                                                    .qualifiedName(),
                                                            (command, entity, context) -> {
                                                                entity.redeem(
                                                                        command.payloadAs(RedeemGiftCard.class),
                                                                        EventAppender.forContext(context)
                                                                );
                                                                return MessageStream.empty().cast();
                                                            }
                                                    )
                                                    // tag::entity-declarative-interceptor[]
                                                    .commandHandlerInterceptor((command, entity, context, chain) -> {
                                                        if (entity != null && entity.isRedeemed()) {
                                                            return MessageStream.failed(new IllegalStateException(
                                                                    "Gift card already redeemed"
                                                            ));
                                                        }
                                                        return chain.proceed(command, entity, context);
                                                    })
                                                    // end::entity-declarative-interceptor[]
                                                    .entityEvolver((entity, event, context) -> {
                                                        entity.on(event.payloadAs(GiftCardRedeemed.class));
                                                        return entity;
                                                    })
                                                    .build();
                                        })
                                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(GiftCard::new))
                                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(
                                                Tag.of("cardId", id)))
                                        .entityIdResolver(c -> new AnnotationBasedEntityIdResolver<>())
                                        .build()
        );
    }
}
