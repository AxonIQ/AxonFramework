package org.axonframework.examples.demo.university.entity.hierarchy;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public class CourseEntityHierarchyConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, CourseEntity.class)
        );
    }

    private CourseEntityHierarchyConfiguration() {
        // Prevent instantiation
    }
}
