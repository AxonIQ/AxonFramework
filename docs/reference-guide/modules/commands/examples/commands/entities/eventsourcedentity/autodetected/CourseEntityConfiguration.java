package commands.entities.eventsourcedentity.autodetected;

// tag::autodetected-configuration[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public class CourseEntityConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, CourseEntity.class) // <1>
        );
    }
}
// end::autodetected-configuration[]
