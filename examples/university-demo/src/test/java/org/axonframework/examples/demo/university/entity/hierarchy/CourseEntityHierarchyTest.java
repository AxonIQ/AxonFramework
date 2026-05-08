package org.axonframework.examples.demo.university.entity.hierarchy;

import org.axonframework.examples.demo.university.faculty.FacultyAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CourseEntityHierarchyTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = FacultyAxonTestFixture.slice(CourseEntityHierarchyConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Nested
    class WhenDropEnrollment {

        @Test
        void givenCourseWithEnrolledStudent_whenDropEnrollment_thenEnrollmentDropped() {
            // given — a course with one enrolled student
            String courseId = "course-1";
            String studentId = "student-1";
            String reason = "Schedule conflict";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing", 5))
                    .event(new StudentEnrolled(courseId, studentId))
                    .when()
                    .command(new DropEnrollment(courseId, studentId, reason))
                    .then()
                    .events(new EnrollmentDropped(courseId, studentId, reason));
        }

        @Test
        void givenDroppedEnrollment_whenDropAgain_thenException() {
            // given — enrollment already dropped
            String courseId = "course-2";
            String studentId = "student-2";
            String reason = "Schedule conflict";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing", 5))
                    .event(new StudentEnrolled(courseId, studentId))
                    .event(new EnrollmentDropped(courseId, studentId, reason))
                    .when()
                    .command(new DropEnrollment(courseId, studentId, "Another reason"))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Enrollment already dropped")
                    );
        }
    }
}
