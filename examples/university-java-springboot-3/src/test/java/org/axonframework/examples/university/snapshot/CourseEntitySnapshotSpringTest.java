package org.axonframework.examples.university.snapshot;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolver;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the declarative snapshot configuration shown in the reference guide works
 * correctly in a Spring Boot context.
 */
@AxonSpringBootTest(
        classes = CourseEntitySnapshotSpringTest.TestApplication.class,
        properties = "axon.axonserver.enabled=false"
)
class CourseEntitySnapshotSpringTest {

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class TestApplication {

        @Bean
        public SnapshotStore snapshotStore() {
            return new InMemorySnapshotStore(); // not for production
        }

        @Bean
        public EventSourcedEntityModule<String, SnapshotCourseEntity> courseEntityModule() {
            return EventSourcedEntityModule.declarative(String.class, SnapshotCourseEntity.class)
                    .messagingModel((config, builder) -> AnnotatedEntityMetamodel.forConcreteType(
                            SnapshotCourseEntity.class,
                            config.getComponent(ParameterResolverFactory.class),
                            config.getComponent(MessageTypeResolver.class),
                            config.getComponent(MessageConverter.class),
                            config.getComponent(EventConverter.class)
                    ))
                    .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(SnapshotCourseEntity::new))
                    .criteriaResolver(c -> new AnnotationBasedEventCriteriaResolver<>(
                            SnapshotCourseEntity.class, String.class, c
                    ))
                    .entityIdResolver(c -> new AnnotationBasedEntityIdResolver<>())
                    .snapshotPolicy(c -> SnapshotPolicy.afterEvents(3))
                    .build();
        }
    }

    @Autowired
    private AxonTestFixture fixture;

    @Nested
    class WhenCreateCourse {

        @Test
        void givenNoCourse_whenCreateCourse_thenCourseCreated() {
            // given
            String courseId = UUID.randomUUID().toString();

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .when()
                    .command(new CreateSnapshotCourse(courseId, "Event Sourcing in Practice", 10))
                    .then()
                    .events(new SnapshotCourseCreated(courseId, "Event Sourcing in Practice", 10));
        }
    }

    @Nested
    class WhenEnrollStudent {

        @Test
        void givenExistingCourse_whenEnrollStudent_thenStudentEnrolled() {
            // given — create the course via given().command(), then enroll
            String courseId = UUID.randomUUID().toString();

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateSnapshotCourse(courseId, "Event Sourcing", 5))
                    .when()
                    .command(new EnrollSnapshotStudent(courseId, "student-1"))
                    .then()
                    .events(new SnapshotStudentEnrolled(courseId, "student-1"));
        }

        @Test
        void givenCourseWithManyEvents_whenEnrollStudent_thenSnapshotTriggeredAndStudentEnrolled() {
            // given — 4 events appended, crossing the afterEvents(3) snapshot threshold
            String courseId = UUID.randomUUID().toString();

            // when / then — snapshot fires after 3 events; entity loads from snapshot + delta
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateSnapshotCourse(courseId, "Event Sourcing", 10))
                    .command(new EnrollSnapshotStudent(courseId, "student-a"))
                    .command(new EnrollSnapshotStudent(courseId, "student-b"))
                    .command(new EnrollSnapshotStudent(courseId, "student-c"))
                    .when()
                    .command(new EnrollSnapshotStudent(courseId, "student-d"))
                    .then()
                    .events(new SnapshotStudentEnrolled(courseId, "student-d"));
        }

        @Test
        void givenFullCourse_whenEnrollStudent_thenException() {
            // given — course with capacity 1, already full
            String courseId = UUID.randomUUID().toString();

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateSnapshotCourse(courseId, "Event Sourcing", 1))
                    .command(new EnrollSnapshotStudent(courseId, "student-existing"))
                    .when()
                    .command(new EnrollSnapshotStudent(courseId, "student-new"))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Course is full")
                    );
        }
    }
}
