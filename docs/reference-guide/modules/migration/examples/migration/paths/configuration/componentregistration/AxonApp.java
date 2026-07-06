package migration.paths.configuration.componentregistration;

// tag::component-registration[]
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

class AxonApp {

    public static void main(String[] args) {
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // Register a custom component via ComponentRegistry
        configurer.componentRegistry(cr -> cr.registerComponent(
                MyService.class,
                config -> new MyService())
        );

        // Register a command bus (dedicated method on MessagingConfigurer)
        configurer.registerCommandBus(config -> new SimpleCommandBus(config.getComponent(UnitOfWorkFactory.class)));
    }
}
// end::component-registration[]

class MyService {
}
