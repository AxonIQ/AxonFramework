package commands.configuration.decoration;

// tag::component-decoration[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.ComponentDecorator;
import org.axonframework.messaging.commandhandling.CommandBus;

public class AxonConfig {

    public void decorateCommandBus(ApplicationConfigurer configurer) {
        configurer.componentRegistry(registry -> registry.registerDecorator(
                CommandBus.class,
                0, // Integer defining the decoration order
                (config, name, commandBus) -> new LoggingCommandBus(commandBus)
        ));
    }
}
// end::component-decoration[]
