package events.eventprocessors.streaming.sequencingpolicy.onhandler;

// tag::sequencing-policy-on-handler[]
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;

@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"studentId"})
class CustomEventHandlingComponent {

    @EventHandler
    @SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"courseId"})
    public void handle(CourseCreatedEvent event) {
        // Handler logic
    }
}
// end::sequencing-policy-on-handler[]

record CourseCreatedEvent(String courseId, String title) {

}
