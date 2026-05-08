package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.examples.demo.university.faculty.FacultyAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CourseEntityTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = FacultyAxonTestFixture.slice(CourseEntityConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Nested
    class WhenCreateCourse {

        @Test
        void givenNoCourse_whenCreateCourse_thenCourseCreated() {
            // given
            String courseId = "course-1";
            String title = "Event Sourcing in Practice";
            int capacity = 10;

            // when / then
            fixture.given()
                    .when()
                    .command(new CreateCourse(courseId, title, capacity))
                    .then()
                    .events(new CourseCreated(courseId, title, capacity));
        }

        @Test
        void givenNoCourse_whenCreateCourseWithZeroCapacity_thenException() {
            // given
            String courseId = "course-2";

            // when / then
            fixture.given()
                    .when()
                    .command(new CreateCourse(courseId, "Some Course", 0))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Capacity must be positive")
                    );
        }
    }

    @Nested
    class WhenEnrollStudent {

        @Test
        void givenExistingCourse_whenEnrollStudent_thenStudentEnrolled() {
            // given
            String courseId = "course-3";
            String studentId = "student-1";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing", 5))
                    .when()
                    .command(new EnrollStudent(courseId, studentId))
                    .then()
                    .events(new StudentEnrolled(courseId, studentId));
        }

        @Test
        void givenFullCourse_whenEnrollStudent_thenException() {
            // given — course with capacity 1, already filled
            String courseId = "course-4";
            String existingStudentId = "student-existing";
            String newStudentId = "student-new";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing", 1))
                    .event(new StudentEnrolled(courseId, existingStudentId))
                    .when()
                    .command(new EnrollStudent(courseId, newStudentId))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Course is full")
                    );
        }
    }
}
