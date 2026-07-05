package tuning.snapshotting.annotationbased.combinedthresholds;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.Snapshotting;

// tag::snapshot-combined-thresholds[]
@EventSourcedEntity
@Snapshotting(afterEvents = 100, afterSourcingTime = "PT5S")
public class Course {
    // entity behavior omitted for brevity
}
// end::snapshot-combined-thresholds[]
