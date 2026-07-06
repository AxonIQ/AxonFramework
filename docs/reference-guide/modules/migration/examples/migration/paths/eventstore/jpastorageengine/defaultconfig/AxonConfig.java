package migration.paths.eventstore.jpastorageengine.defaultconfig;

// tag::jpa-storage-engine-configuration[]
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

public class AxonConfig {

    public void configureStorageEngine(
            EventSourcingConfigurer configurer,
            EntityManagerFactory factory,
            EventConverter eventConverter
    ) {
        configurer.registerEventStorageEngine(
                config -> new AggregateBasedJpaEventStorageEngine(
                        new JpaTransactionalExecutorProvider(factory),
                        eventConverter,
                        engineConfig -> engineConfig
                )
        );
    }
}
// end::jpa-storage-engine-configuration[]
