package events.infrastructure.inmemory.springboot;

// tag::inmemory-storage-engine-springboot[]
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public EventStorageEngine storageEngine() {
        return new InMemoryEventStorageEngine();
    }
}
// end::inmemory-storage-engine-springboot[]
