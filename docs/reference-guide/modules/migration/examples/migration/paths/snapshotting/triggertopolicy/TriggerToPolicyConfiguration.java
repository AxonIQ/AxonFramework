package migration.paths.snapshotting.triggertopolicy;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * Shows how a repository-level trigger definition (Axon Framework 4) is replaced by an entity-level
 * {@link SnapshotPolicy} (Axon Framework 5.1), as shown on the snapshotting migration page.
 */
class TriggerToPolicyConfiguration {

    static EventSourcedEntityModule<String, Account> configure() {
        // tag::snapshot-trigger-to-policy[]
        SnapshotPolicy snapshotPolicy =
                SnapshotPolicy.afterEvents(100)
                              .or(SnapshotPolicy.whenSourcingTimeExceeds(java.time.Duration.ofMillis(500)));

        EventSourcedEntityModule<String, Account> accountModule =
                EventSourcedEntityModule.declarative(String.class, Account.class)
                // end::snapshot-trigger-to-policy[]
                                        .messagingModel((config, model) -> model.build())
                                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(Account::new))
                                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(
                                                Tag.of("accountId", id)
                                        ))
                // tag::snapshot-trigger-to-policy[]
                                        .snapshotPolicy(c -> snapshotPolicy)
                                        .build();
        // end::snapshot-trigger-to-policy[]
        return accountModule;
    }
}

/**
 * Placeholder entity used by this sample; its command handling and event sourcing behavior is not relevant here.
 */
class Account {
}
