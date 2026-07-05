package commands.entities.entitycreator.firstevent;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

class CourseEntityConfiguration {

    static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
            EventSourcedEntityModule.declarative(String.class, CourseEntity.class)
                .messagingModel((config, model) -> model.build())
                // tag::first-event-entity-factory[]
                .entityFactory(c -> EventSourcedEntityFactory.fromEventMessage((id, firstEvent) -> {
                    CourseCreatedEvent created = firstEvent.payloadAs(CourseCreatedEvent.class);
                    return new CourseEntity(created.courseId(), created.capacity());
                }))
                // end::first-event-entity-factory[]
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("courseId", id)))
                .build()
        );
    }
}
