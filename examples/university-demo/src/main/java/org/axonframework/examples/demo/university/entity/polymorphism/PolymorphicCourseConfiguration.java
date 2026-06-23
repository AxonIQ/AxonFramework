package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public class PolymorphicCourseConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, CourseEntity.class)
        );
    }

    private PolymorphicCourseConfiguration() {
        // Prevent instantiation
    }
}
