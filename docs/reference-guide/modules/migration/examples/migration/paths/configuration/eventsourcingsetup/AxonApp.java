package migration.paths.configuration.eventsourcingsetup;

// tag::eventsourcing-configurer-setup[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        // Register components, entities, event handlers...

        AxonConfiguration configuration = configurer.build();
        configuration.start();
    }
}
// end::eventsourcing-configurer-setup[]
