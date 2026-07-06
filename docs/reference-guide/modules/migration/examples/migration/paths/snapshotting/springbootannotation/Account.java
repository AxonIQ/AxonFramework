package migration.paths.snapshotting.springbootannotation;

import org.axonframework.eventsourcing.annotation.Snapshotting;
import org.axonframework.extension.spring.stereotype.EventSourced;

// tag::spring-boot-snapshotting-annotation[]
@EventSourced
@Snapshotting(afterEvents = 100)
public class Account {
    // entity behavior omitted for brevity
}
// end::spring-boot-snapshotting-annotation[]
