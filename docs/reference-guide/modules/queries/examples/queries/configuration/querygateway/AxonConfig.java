package queries.configuration.querygateway;

// tag::query-gateway-configuration-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;

public class AxonConfig {

    public void registerCustomQueryGateway(MessagingConfigurer configurer) {
        configurer.componentRegistry(registry -> registry.registerComponent(
                QueryGateway.class,
                config -> new DefaultQueryGateway(
                        config.getComponent(QueryBus.class),
                        config.getComponent(MessageTypeResolver.class),
                        config.getComponent(QueryPriorityCalculator.class),
                        config.getComponent(MessageConverter.class)
                )
        ));
    }
}
// end::query-gateway-configuration-api[]
