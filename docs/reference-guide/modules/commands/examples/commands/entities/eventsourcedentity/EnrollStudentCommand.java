package commands.entities.eventsourcedentity;

// tag::target-entity-id[]
import org.axonframework.modelling.annotation.TargetEntityId;

public record EnrollStudentCommand(
        @TargetEntityId String courseId, // <1>
        String studentId
) {}
// end::target-entity-id[]
