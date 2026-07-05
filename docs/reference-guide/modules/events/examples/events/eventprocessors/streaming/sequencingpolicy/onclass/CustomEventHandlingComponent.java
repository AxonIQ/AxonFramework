package events.eventprocessors.streaming.sequencingpolicy.onclass;

// tag::sequencing-policy-on-class[]
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;

@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = {"studentId"})
class CustomEventHandlingComponent {

    @EventHandler
    public void handle(StudentEnrolledEvent event) {
        // Handler logic
    }
}
// end::sequencing-policy-on-class[]

record StudentEnrolledEvent(String studentId, String courseId) {

}
