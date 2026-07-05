package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.MetadataRoutingStrategy;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::spring-routing-strategy[]
@Configuration
public class AxonConfig {

    @Bean
    public RoutingStrategy routingStrategy() {
        return new MetadataRoutingStrategy("tenantId"); // <1>
    }
}
// end::spring-routing-strategy[]
