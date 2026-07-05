package events.infrastructure.jpa.configapi;

// tag::jpa-storage-engine-configapi[]
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

public class AxonConfig {

    public void configureStorageEngine(
            EventSourcingConfigurer configurer,
            EntityManagerFactory entityManagerFactory,
            EventConverter eventConverter
    ) {
        configurer.registerEventStorageEngine(
                config -> new AggregateBasedJpaEventStorageEngine(
                        new JpaTransactionalExecutorProvider(entityManagerFactory),
                        eventConverter,
                        // The AggregateBasedJpaEventStorageEngineConfiguration lambda allows for further customization.
                        engineConfig -> engineConfig
                )
        );
    }
}
// end::jpa-storage-engine-configapi[]
