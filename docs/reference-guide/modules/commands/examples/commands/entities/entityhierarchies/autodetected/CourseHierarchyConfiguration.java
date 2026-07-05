package commands.entities.entityhierarchies.autodetected;

// tag::autodetected-registration[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public class CourseHierarchyConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, CourseEntity.class)
        );
    }
}
// end::autodetected-registration[]
