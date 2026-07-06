package migration.paths.configuration.conditionalregistration;

// tag::conditional-registration[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

class AxonApp {

    public static void main(String[] args) {
        MessagingConfigurer configurer = MessagingConfigurer.create();

        configurer.componentRegistry(cr -> {
            // Only registers if no MyService is already present
            cr.registerIfNotPresent(MyService.class, config -> new MyService());
        });
    }
}
// end::conditional-registration[]

class MyService {
}
