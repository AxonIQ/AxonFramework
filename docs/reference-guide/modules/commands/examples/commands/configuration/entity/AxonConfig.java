package commands.configuration.entity;

// tag::entity-registration[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;

public class AxonConfig {

    public void configureEntity() {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

        // Register an entity module
        configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, GiftCard.class)
        );
    }
}
// end::entity-registration[]
