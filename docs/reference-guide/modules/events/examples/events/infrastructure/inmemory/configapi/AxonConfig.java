package events.infrastructure.inmemory.configapi;

// tag::inmemory-storage-engine-configapi[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;

public class AxonConfig {

    public void configureStorageEngine(EventSourcingConfigurer configurer) {
        configurer.registerEventStorageEngine(config -> new InMemoryEventStorageEngine());
    }
}
// end::inmemory-storage-engine-configapi[]
