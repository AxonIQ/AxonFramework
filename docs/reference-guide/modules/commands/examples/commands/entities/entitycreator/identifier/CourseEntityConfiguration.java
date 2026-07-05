package commands.entities.entitycreator.identifier;

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
                // tag::identifier-entity-factory[]
                .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(courseId -> new CourseEntity(courseId)))
                // end::identifier-entity-factory[]
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("courseId", id)))
                .build()
        );
    }
}
