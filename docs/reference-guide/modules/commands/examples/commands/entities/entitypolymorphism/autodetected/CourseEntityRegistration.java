package commands.entities.entitypolymorphism.autodetected;

// The page displays the imports and the registration method as one snippet. The method is
// deliberately left at column 0 inside the wrapper class so both tag regions share the same
// indentation and the rendered snippet matches the page byte for byte.
// tag::autodetected-registration[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

// end::autodetected-registration[]
class CourseEntityRegistration {

// tag::autodetected-registration[]
static void registerCourseEntity(EventSourcingConfigurer configurer) {
    configurer.registerEntity(
        EventSourcedEntityModule.autodetected(String.class, CourseEntity.class) // <1>
    );
}
// end::autodetected-registration[]
}
