package migration.paths.aggregates.configurationmigration;

// tag::register-entity-module[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public class AxonConfig {

    public void configure(EventSourcingConfigurer configurer) {
        configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, GiftCard.class)
        );
    }
}
// end::register-entity-module[]
