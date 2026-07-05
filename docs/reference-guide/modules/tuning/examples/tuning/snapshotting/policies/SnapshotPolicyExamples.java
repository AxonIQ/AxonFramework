package tuning.snapshotting.policies;

import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;

import java.time.Duration;

/**
 * Illustrates composing a {@link SnapshotPolicy} out of the standard building blocks on the snapshotting page.
 */
class SnapshotPolicyExamples {

    static SnapshotPolicy combinedPolicy() {
        // tag::combined-snapshot-policy[]
        SnapshotPolicy snapshotPolicy =
                SnapshotPolicy.afterEvents(5)
                              .or(SnapshotPolicy.whenSourcingTimeExceeds(
                                  Duration.ofMillis(500)
                              ));
        // end::combined-snapshot-policy[]
        return snapshotPolicy;
    }
}
