package commands.configuration.registration;

// tag::basic-component-registration[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.ComponentBuilder;

public class AxonConfig {

    public void registerMyService(ApplicationConfigurer configurer) {
        ComponentBuilder<MyService> serviceBuilder = config -> new MyService(
                config.getComponent(SomeDependency.class)
        );
        configurer.componentRegistry(
                registry -> registry.registerComponent(MyService.class, serviceBuilder)
        );
    }
}
// end::basic-component-registration[]
