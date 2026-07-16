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
package commands.entities.entitypolymorphism.declarative;

import commands.entities.entitypolymorphism.declarative.CourseHierarchy.CourseEntity;
import commands.entities.entitypolymorphism.declarative.CourseHierarchy.InPersonCourse;
import commands.entities.entitypolymorphism.declarative.CourseHierarchy.OnlineCourse;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;

public class CourseEntityConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
            EventSourcedEntityModule.declarative(String.class, CourseEntity.class)
                .messagingModel((config, model) -> {
                    MessageTypeResolver resolver = config.getComponent(MessageTypeResolver.class);
                    // Creational command handler omitted; see the event-sourced entities page.
                    return model
                        // tag::polymorphic-instance-command-handler[]
                        // Subtype-specific instance command handler. Cast to the concrete type.
                        .instanceCommandHandler(
                            resolver.resolveOrThrow(UpdatePlatformUrl.class).qualifiedName(),
                            (command, entity, context) -> {
                                OnlineCourse online = (OnlineCourse) entity; // <1>
                                UpdatePlatformUrl cmd = command.payloadAs(UpdatePlatformUrl.class);
                                EventAppender.forContext(context).append(
                                        new PlatformUrlUpdated(online.courseId, cmd.newUrl())
                                );
                                return MessageStream.empty().cast();
                            }
                        )
                        // end::polymorphic-instance-command-handler[]
                        .entityEvolver((entity, event, context) -> {
                            if (entity instanceof OnlineCourse online
                                    && event.type().qualifiedName().equals(
                                            resolver.resolveOrThrow(PlatformUrlUpdated.class).qualifiedName())) {
                                online.platformUrl = event.payloadAs(PlatformUrlUpdated.class).newUrl();
                            }
                            return entity;
                        })
                        .build();
                })
                // tag::polymorphic-entity-factory[]
                // Entity factory: pick the concrete type based on the first event.
                .entityFactory(c -> EventSourcedEntityFactory.fromEventMessage((id, firstEvent) -> { // <2>
                    CourseCreated e = firstEvent.payloadAs(CourseCreated.class);
                    return switch (e.courseType()) {
                        case ONLINE    -> new OnlineCourse(e);
                        case IN_PERSON -> new InPersonCourse(e);
                    };
                }))
                // end::polymorphic-entity-factory[]
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("courseId", id)))
                .entityIdResolver(c -> new AnnotationBasedEntityIdResolver<>())
                .build()
        );
    }
}
