package migration.paths.snapshotting.declarativeconfiguration;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * Shows how snapshotting is enabled declaratively by adding a {@link SnapshotPolicy} to the
 * {@link EventSourcedEntityModule} and registering a {@link SnapshotStore}, as shown on the snapshotting
 * migration page.
 */
class DeclarativeSnapshotConfiguration {

    static void configure() {
        // tag::snapshot-declarative-configuration[]
        SnapshotPolicy snapshotPolicy = SnapshotPolicy.afterEvents(250);

        EventSourcedEntityModule<String, Account> accountModule =
                EventSourcedEntityModule.declarative(String.class, Account.class)
                // end::snapshot-declarative-configuration[]
                                        .messagingModel((config, model) -> model.build())
                                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(Account::new))
                                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(
                                                Tag.of("accountId", id)
                                        ))
                // tag::snapshot-declarative-configuration[]
                                        .snapshotPolicy(c -> snapshotPolicy)
                                        .build();

        EventSourcingConfigurer.create()
                .componentRegistry(cr -> cr.registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore()))
                .componentRegistry(cr -> cr.registerModule(accountModule))
                .start();
        // end::snapshot-declarative-configuration[]
    }
}

/**
 * Placeholder entity used by this sample; its command handling and event sourcing behavior is not relevant here.
 */
class Account {
}
