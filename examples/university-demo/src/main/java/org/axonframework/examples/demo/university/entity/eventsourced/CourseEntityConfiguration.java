package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public class CourseEntityConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, CourseEntity.class)
        );
    }

    private CourseEntityConfiguration() {
        // Prevent instantiation
    }
}
