package queries.configuration.querybus;

// tag::query-bus-configuration-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

public class AxonConfig {

    public void registerCustomQueryBus(MessagingConfigurer configurer) {
        configurer.registerQueryBus(
                config -> new SimpleQueryBus(config.getComponent(UnitOfWorkFactory.class))
        );
    }
}
// end::query-bus-configuration-api[]
