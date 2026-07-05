package tuning.snapshotting.annotationbased.afterevents;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.Snapshotting;

// tag::snapshot-after-events[]
@EventSourcedEntity
@Snapshotting(afterEvents = 100)
public class Course {
    // entity behavior omitted for brevity
}
// end::snapshot-after-events[]
