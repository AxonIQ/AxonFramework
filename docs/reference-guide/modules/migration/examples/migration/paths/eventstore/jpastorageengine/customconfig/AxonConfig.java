package migration.paths.eventstore.jpastorageengine.customconfig;

// tag::jpa-storage-engine-custom-configuration[]
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
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
                                .batchSize(100)
                                .gapCleaningThreshold(250)
                                .gapTimeout(10000)
                                .lowestGlobalSequence(1)
                                .maxGapOffset(60000)
                                .persistenceExceptionResolver(
                                        config.getComponent(PersistenceExceptionResolver.class)
                                )
                )
        );
    }
}
// end::jpa-storage-engine-custom-configuration[]
