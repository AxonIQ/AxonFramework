package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.examples.demo.university.faculty.FacultyAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that snapshot configuration via the declarative builder works correctly.
 * The snapshot policy is set to {@code afterEvents(3)}, so a snapshot is triggered
 * after more than 3 events have been applied.
 */
class CourseEntitySnapshotTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = FacultyAxonTestFixture.slice(CourseEntitySnapshotConfiguration::configure);
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
            String courseId = "snapshot-course-1";
            String title = "Event Sourcing in Practice";
            int capacity = 10;

            // when / then
            fixture.given()
                    .when()
                    .command(new CreateCourse(courseId, title, capacity))
                    .then()
                    .events(new CourseCreated(courseId, title, capacity));
        }
    }

    @Nested
    class WhenEnrollStudent {

        @Test
        void givenExistingCourse_whenEnrollStudent_thenStudentEnrolled() {
            // given
            String courseId = "snapshot-course-2";
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
        void givenCourseWithManyEvents_whenEnrollStudent_thenSnapshotTriggeredAndStudentEnrolled() {
            // given — more than 3 events to trigger the snapshot policy (afterEvents(3))
            String courseId = "snapshot-course-3";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing", 10))
                    .event(new StudentEnrolled(courseId, "student-a"))
                    .event(new StudentEnrolled(courseId, "student-b"))
                    .event(new StudentEnrolled(courseId, "student-c"))
                    .when()
                    .command(new EnrollStudent(courseId, "student-d"))
                    .then()
                    .events(new StudentEnrolled(courseId, "student-d"));
        }

        @Test
        void givenFullCourse_whenEnrollStudent_thenException() {
            // given — course with capacity 1, already filled
            String courseId = "snapshot-course-4";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing", 1))
                    .event(new StudentEnrolled(courseId, "student-existing"))
                    .when()
                    .command(new EnrollStudent(courseId, "student-new"))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown)
                            .hasMessageContaining("Course is full")
                    );
        }
    }
}
