package commands.entities.eventsourcedentity.declarative;

// tag::declarative-configuration[]
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;

public class CourseEntityConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(
            EventSourcedEntityModule.declarative(String.class, CourseEntity.class)
                .messagingModel((config, model) -> {
                    MessageTypeResolver resolver = config.getComponent(MessageTypeResolver.class); // <1>
                    return model
                        .creationalCommandHandler( // <2>
                            resolver.resolveOrThrow(CreateCourseCommand.class).qualifiedName(),
                            (command, context) -> {
                                CourseEntity.create(command.payloadAs(CreateCourseCommand.class), // <3>
                                                   EventAppender.forContext(context));
                                return MessageStream.empty().cast();
                            }
                        )
                        .instanceCommandHandler( // <4>
                            resolver.resolveOrThrow(EnrollStudentCommand.class).qualifiedName(),
                            (command, entity, context) -> {
                                entity.enroll(command.payloadAs(EnrollStudentCommand.class), // <5>
                                              EventAppender.forContext(context));
                                return MessageStream.empty().cast();
                            }
                        )
                        .entityEvolver((entity, event, context) -> { // <6>
                            QualifiedName courseCreated = resolver.resolveOrThrow(CourseCreatedEvent.class).qualifiedName();
                            QualifiedName studentEnrolled = resolver.resolveOrThrow(StudentEnrolledEvent.class).qualifiedName();
                            if (event.type().qualifiedName().equals(courseCreated)) {
                                entity.on(event.payloadAs(CourseCreatedEvent.class));
                            } else if (event.type().qualifiedName().equals(studentEnrolled)) {
                                entity.on(event.payloadAs(StudentEnrolledEvent.class));
                            }
                            return entity;
                        })
                        .build();
                })
                .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(CourseEntity::new)) // <7>
                .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("courseId", id))) // <8>
                .entityIdResolver(c -> new AnnotationBasedEntityIdResolver<>()) // <9>
                .build()
        );
    }
}
// end::declarative-configuration[]
