package org.axonframework.examples.university.hierarchy;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that entity hierarchy configuration shown in the reference guide works
 * correctly in a Spring Boot context.
 */
@AxonSpringBootTest(
        classes = CourseEntityHierarchySpringTest.TestApplication.class,
        properties = "axon.axonserver.enabled=false"
)
class CourseEntityHierarchySpringTest {

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class TestApplication {

        @Bean
        public EventSourcedEntityModule<String, CourseEntity> courseEntityModule() {
            return EventSourcedEntityModule.autodetected(String.class, CourseEntity.class);
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
                    .command(new CreateCourse(courseId, "Event Sourcing in Practice", 10))
                    .then()
                    .events(new CourseCreated(courseId, "Event Sourcing in Practice", 10));
        }
    }

    @Nested
    class WhenEnrollStudent {

        @Test
        void givenExistingCourse_whenEnrollStudent_thenStudentEnrolled() {
            // given
            String courseId = UUID.randomUUID().toString();

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateCourse(courseId, "Event Sourcing", 5))
                    .when()
                    .command(new EnrollStudent(courseId, "student-1"))
                    .then()
                    .events(new StudentEnrolled(courseId, "student-1"));
        }

        @Test
        void givenFullCourse_whenEnrollStudent_thenException() {
            // given — capacity 1, already full
            String courseId = UUID.randomUUID().toString();

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateCourse(courseId, "Event Sourcing", 1))
                    .command(new EnrollStudent(courseId, "student-existing"))
                    .when()
                    .command(new EnrollStudent(courseId, "student-new"))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Course is full")
                    );
        }
    }

    @Nested
    class WhenDropEnrollment {

        @Test
        void givenCourseWithEnrolledStudent_whenDropEnrollment_thenEnrollmentDropped() {
            // given
            String courseId = UUID.randomUUID().toString();
            String studentId = "student-1";

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateCourse(courseId, "Event Sourcing", 5))
                    .command(new EnrollStudent(courseId, studentId))
                    .when()
                    .command(new DropEnrollment(courseId, studentId, "Schedule conflict"))
                    .then()
                    .events(new EnrollmentDropped(courseId, studentId, "Schedule conflict"));
        }

        @Test
        void givenDroppedEnrollment_whenDropAgain_thenException() {
            // given — enrollment already dropped
            String courseId = UUID.randomUUID().toString();
            String studentId = "student-1";

            // when / then
            fixture.given()
                    .noPriorActivity()
                    .command(new CreateCourse(courseId, "Event Sourcing", 5))
                    .command(new EnrollStudent(courseId, studentId))
                    .command(new DropEnrollment(courseId, studentId, "Schedule conflict"))
                    .when()
                    .command(new DropEnrollment(courseId, studentId, "Another reason"))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Enrollment already dropped")
                    );
        }
    }
}
