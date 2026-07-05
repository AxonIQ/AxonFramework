package commands.entities.entityhierarchies;

// tag::declarative-child-wiring[]
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.modelling.entity.ConcreteEntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;
import org.axonframework.modelling.entity.child.ChildEntityFieldDefinition;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;

public class AxonConfiguration {

    // The body of the messagingModel((config, model) -> { ... }) callback:
    EntityMetamodel<CourseEntity> buildMetamodel(Configuration config, EntityMetamodelBuilder<CourseEntity> model) {
        MessageTypeResolver resolver = config.getComponent(MessageTypeResolver.class);

        // Build the child metamodel: same shape as a parent metamodel, without children.
        EntityMetamodel<EnrollmentEntity> enrollmentMetamodel = ConcreteEntityMetamodel
                .forEntityClass(EnrollmentEntity.class)
                // ... child command handlers and evolver
                .build();

        // Attach it to the parent metamodel via addChild.
        return model
                // ... parent command handlers and evolver
                .addChild(EntityChildMetamodel // <1>
                        .list(CourseEntity.class, enrollmentMetamodel)
                        .childEntityFieldDefinition(ChildEntityFieldDefinition.forGetterEvolver( // <2>
                                CourseEntity::getEnrollments,
                                CourseEntity::withEnrollments
                        ))
                        .commandTargetResolver((candidates, command, ctx) -> { // <3>
                            DropEnrollment cmd = command.payloadAs(DropEnrollment.class);
                            return candidates.stream()
                                             .filter(e -> e.getStudentId().equals(cmd.studentId()))
                                             .findFirst()
                                             .orElse(null);
                        })
                        .eventTargetMatcher((child, event, ctx) -> { // <4>
                            if (event.type().qualifiedName().equals(resolver.resolveOrThrow(EnrollmentDropped.class).qualifiedName())) {
                                EnrollmentDropped e = event.payloadAs(EnrollmentDropped.class);
                                return child.getStudentId().equals(e.studentId());
                            }
                            return false;
                        })
                        .build()
                )
                .build();
    }
}
// end::declarative-child-wiring[]
