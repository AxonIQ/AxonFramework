package migration.paths.configuration.componentdefinition;

// tag::component-definition[]
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

class AxonApp {

    public static void main(String[] args) {
        MessagingConfigurer configurer = MessagingConfigurer.create();

        configurer.componentRegistry(cr -> cr.registerComponent(
                ComponentDefinition.ofType(MyService.class)
                                   .withBuilder(config -> new MyService())
                                   .onStart(0, MyService::start)
                                   .onShutdown(0, MyService::shutdown)
        ));
    }
}
// end::component-definition[]

class MyService {

    void start() {
    }

    void shutdown() {
    }
}
