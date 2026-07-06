package migration.paths.configuration.escapehatches;

// tag::configurer-escape-hatches[]
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

class AxonApp {

    public static void main(String[] args) {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

        // Access the modelling layer
        configurer.modelling(modellingConfigurer -> modellingConfigurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, MyEntity.class)
        ));

        // Access the messaging layer
        configurer.messaging(messagingConfigurer -> messagingConfigurer.registerCommandBus(
                config -> new SimpleCommandBus(config.getComponent(UnitOfWorkFactory.class))
        ));
    }
}
// end::configurer-escape-hatches[]

@org.axonframework.eventsourcing.annotation.EventSourcedEntity
class MyEntity {
}
