package migration.paths.snapshotting.springbootprogrammatic;

import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.axonserver.connector.snapshot.AxonServerSnapshotStore;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::spring-boot-snapshotting-programmatic[]
@Configuration
class AccountConfiguration {

    @Bean
    EventSourcedEntityModule<String, Account> accountModule() {
        return EventSourcedEntityModule.declarative(String.class, Account.class)
                // end::spring-boot-snapshotting-programmatic[]
                .messagingModel((config, model) -> model.build())
                .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(Account::new))
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(
                        Tag.of("accountId", id)
                ))
                // tag::spring-boot-snapshotting-programmatic[]
                .snapshotPolicy(c -> SnapshotPolicy.afterEvents(250)
                        .or(SnapshotPolicy.whenEventMatches(
                                msg -> msg.type().qualifiedName().equals(
                                        new QualifiedName(AccountClosed.class)
                                )
                        )))
                .build();
    }

    @Bean
    SnapshotStore snapshotStore(AxonServerConnectionManager connectionManager, EventConverter converter) {
        return new AxonServerSnapshotStore(connectionManager.getConnection(), converter);
    }
}
// end::spring-boot-snapshotting-programmatic[]

/**
 * Placeholder entity used by this sample; its command handling and event sourcing behavior is not relevant here.
 */
class Account {
}

/**
 * Placeholder event used to demonstrate {@link SnapshotPolicy#whenEventMatches(java.util.function.Predicate)}.
 */
record AccountClosed(String accountId) {
}
