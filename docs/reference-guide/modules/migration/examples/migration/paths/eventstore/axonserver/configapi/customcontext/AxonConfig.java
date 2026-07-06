package migration.paths.eventstore.axonserver.configapi.customcontext;

// tag::axonserver-storage-engine-context[]
import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.axonserver.connector.event.AggregateBasedAxonServerEventStorageEngine;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

public class AxonConfig {

    public void configureStorageEngine(EventSourcingConfigurer configurer,
                                       String context) {
        configurer.registerEventStorageEngine(config -> {
            AxonServerConnectionManager connectionManager =
                    config.getComponent(AxonServerConnectionManager.class);
            return new AggregateBasedAxonServerEventStorageEngine(
                    connectionManager.getConnection(context),
                    config.getComponent(EventConverter.class)
            );
        });
    }
}
// end::axonserver-storage-engine-context[]
