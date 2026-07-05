package commands.commanddispatchers;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.messaging.commandhandling.MetadataRoutingStrategy;
import org.axonframework.messaging.commandhandling.RoutingStrategy;

class RoutingConfigurationExample {

    // tag::custom-routing-strategy-config[]
    // Using Configuration API
    public ApplicationConfigurer configureRouting(ApplicationConfigurer configurer) {
        return configurer.componentRegistry(registry ->
            registry.registerComponent(
                RoutingStrategy.class,
                config -> new MetadataRoutingStrategy("tenantId") // <1>
            )
        );
    }
    // end::custom-routing-strategy-config[]
}
