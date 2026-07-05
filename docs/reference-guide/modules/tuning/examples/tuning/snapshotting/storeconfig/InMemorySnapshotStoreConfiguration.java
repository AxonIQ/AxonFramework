package tuning.snapshotting.storeconfig;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;

/**
 * Registers an {@link InMemorySnapshotStore}, suitable for testing or simple setups, on the snapshotting page.
 */
class InMemorySnapshotStoreConfiguration {

    static void configure(ApplicationConfigurer configurer) {
        configurer.componentRegistry(registry -> {
            // tag::in-memory-snapshot-store[]
            registry.registerComponent(
                    SnapshotStore.class,
                    c -> new InMemorySnapshotStore()
            );
            // end::in-memory-snapshot-store[]
        });
    }
}
