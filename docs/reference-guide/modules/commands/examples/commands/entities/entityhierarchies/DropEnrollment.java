package commands.entities.entityhierarchies;

// tag::drop-enrollment-command[]
import org.axonframework.modelling.annotation.TargetEntityId;

public record DropEnrollment(
        @TargetEntityId String courseId, // <1>
        String studentId,                // <2>
        String reason
) {}
// end::drop-enrollment-command[]
