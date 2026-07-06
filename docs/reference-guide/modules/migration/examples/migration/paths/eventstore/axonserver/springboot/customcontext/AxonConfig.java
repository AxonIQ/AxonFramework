package migration.paths.eventstore.axonserver.springboot.customcontext;

// tag::axonserver-storage-engine-springboot-context[]
import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.axonserver.connector.event.AggregateBasedAxonServerEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public EventStorageEngine storageEngine(AxonServerConnectionManager connectionManager,
                                            EventConverter eventConverter,
                                            @Value("my-context") String context) {
        return new AggregateBasedAxonServerEventStorageEngine(
                connectionManager.getConnection(context),
                eventConverter
        );
    }
}
// end::axonserver-storage-engine-springboot-context[]
