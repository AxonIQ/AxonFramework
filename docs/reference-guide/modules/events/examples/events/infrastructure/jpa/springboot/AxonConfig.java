package events.infrastructure.jpa.springboot;

// tag::jpa-storage-engine-springboot[]
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public EventStorageEngine storageEngine(EntityManagerFactory entityManagerFactory,
                                            EventConverter eventConverter) {
        return new AggregateBasedJpaEventStorageEngine(
                new JpaTransactionalExecutorProvider(entityManagerFactory),
                eventConverter,
                // The AggregateBasedJpaEventStorageEngineConfiguration lambda allows for further customization.
                engineConfig -> engineConfig
        );
    }
}
// end::jpa-storage-engine-springboot[]
