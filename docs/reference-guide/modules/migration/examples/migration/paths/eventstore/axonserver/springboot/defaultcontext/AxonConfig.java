package migration.paths.eventstore.axonserver.springboot.defaultcontext;

// tag::axonserver-storage-engine-springboot[]
import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.axonserver.connector.event.AggregateBasedAxonServerEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public EventStorageEngine storageEngine(AxonServerConnectionManager connectionManager,
                                            EventConverter eventConverter) {
        return new AggregateBasedAxonServerEventStorageEngine(
                connectionManager.getConnection(),
                eventConverter
        );
    }
}
// end::axonserver-storage-engine-springboot[]
