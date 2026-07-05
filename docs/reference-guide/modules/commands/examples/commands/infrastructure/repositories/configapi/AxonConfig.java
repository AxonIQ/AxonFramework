package commands.infrastructure.repositories.configapi;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;

// tag::order-config[]
public class AxonConfig {

    public void configureOrderEntity(EventSourcingConfigurer configurer) {
        configurer.registerEntity(
                EventSourcedEntityModule.autodetected(String.class, Order.class)
        );
    }
}
// end::order-config[]
