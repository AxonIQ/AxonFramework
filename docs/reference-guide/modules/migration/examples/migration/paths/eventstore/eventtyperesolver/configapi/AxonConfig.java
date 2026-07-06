package migration.paths.eventstore.eventtyperesolver.configapi;

// tag::event-type-resolver-configuration[]
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventTypeResolver;
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
                        engineConfig -> engineConfig.eventTypeResolver(
                            EventTypeResolver.withDefaultVersion("0.0.1")
                        )
                )
        );
    }
}
// end::event-type-resolver-configuration[]
