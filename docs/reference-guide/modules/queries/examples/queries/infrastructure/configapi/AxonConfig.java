package queries.infrastructure.configapi;

// tag::query-bus-configapi[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

public class AxonConfig {
    // omitting other configuration methods...
    public void configureQueryBus(MessagingConfigurer configurer) {
        configurer.registerQueryBus(
                config ->  new SimpleQueryBus(config.getComponent(UnitOfWorkFactory.class))
        );
    }
}
// end::query-bus-configapi[]
