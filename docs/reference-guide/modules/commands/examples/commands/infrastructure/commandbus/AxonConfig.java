package commands.infrastructure.commandbus;

import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

// tag::command-bus-config-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class AxonConfig {

    public void configureCommandBus(MessagingConfigurer configurer) {
        configurer.registerCommandBus(
                config -> new SimpleCommandBus(
                        config.getComponent(UnitOfWorkFactory.class)
                )
        );
    }
}
// end::command-bus-config-api[]
