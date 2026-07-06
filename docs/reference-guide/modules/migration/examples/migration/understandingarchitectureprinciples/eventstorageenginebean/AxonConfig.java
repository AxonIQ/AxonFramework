package migration.understandingarchitectureprinciples.eventstorageenginebean;

// tag::event-storage-engine-bean[]
import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.context.annotation.Bean;

class AxonConfig {

    @Bean
    public EventStorageEngine eventStorageEngine(
            AxonServerConnectionManager connectionManager,
            EventConverter eventConverter
    ) {
        return new AxonServerEventStorageEngine(connectionManager.getConnection(), eventConverter);
    }
}
// end::event-storage-engine-bean[]
