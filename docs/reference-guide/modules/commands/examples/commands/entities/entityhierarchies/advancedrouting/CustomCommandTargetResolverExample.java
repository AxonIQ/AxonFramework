package commands.entities.entityhierarchies.advancedrouting;

import java.util.ArrayList;
import java.util.List;

import org.axonframework.modelling.entity.annotation.EntityMember;

class CustomCommandTargetResolverExample {

    // tag::custom-command-target-resolver[]
    @EntityMember(
        routingKey = "studentId",
        commandTargetResolver = PriorityEnrollmentCommandTargetResolver.class // <1>
    )
    private final List<EnrollmentEntity> enrollments = new ArrayList<>();
    // end::custom-command-target-resolver[]
}
