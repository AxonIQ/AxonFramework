package org.axonframework.examples.demo.university.entity.polymorphism;

import org.axonframework.examples.demo.university.faculty.FacultyAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolymorphicCourseEntityTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = FacultyAxonTestFixture.slice(PolymorphicCourseConfiguration::configure);
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Nested
    class WhenCreateCourse {

        @Test
        void givenNoCourse_whenCreateOnlineCourse_thenOnlineCourseCreated() {
            // given
            String courseId = "course-online-1";
            String platformUrl = "https://platform.example.com/course";

            // when / then
            fixture.given()
                    .when()
                    .command(new CreateCourse(courseId, "Event Sourcing Online", 20, CourseType.ONLINE, platformUrl))
                    .then()
                    .events(new CourseCreated(courseId, "Event Sourcing Online", 20, CourseType.ONLINE, platformUrl));
        }
    }

    @Nested
    class WhenUpdatePlatformUrl {

        @Test
        void givenOnlineCourse_whenUpdatePlatformUrl_thenPlatformUrlUpdated() {
            // given — online course exists
            String courseId = "course-online-2";
            String initialUrl = "https://old.example.com";
            String newUrl = "https://new.example.com";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing Online", 10, CourseType.ONLINE, initialUrl))
                    .when()
                    .command(new UpdatePlatformUrl(courseId, newUrl))
                    .then()
                    .events(new PlatformUrlUpdated(courseId, newUrl));
        }

        @Test
        void givenOnlineCourse_whenUpdateLocation_thenException() {
            // given — online course does not handle UpdateLocation
            String courseId = "course-online-3";

            // when / then — UpdateLocation targets InPersonCourse only; on OnlineCourse there is no handler
            fixture.given()
                    .event(new CourseCreated(courseId, "Online Course", 10, CourseType.ONLINE, "https://platform.example.com"))
                    .when()
                    .command(new UpdateLocation(courseId, "Room 101"))
                    .then()
                    .exceptionSatisfies(thrown -> assertThat(thrown).isNotNull());
        }
    }

    @Nested
    class WhenUpdateLocation {

        @Test
        void givenInPersonCourse_whenUpdateLocation_thenLocationUpdated() {
            // given — in-person course exists
            String courseId = "course-inperson-1";
            String initialLocation = "Building A, Room 101";
            String newLocation = "Building B, Room 202";

            // when / then
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing In-Person", 30, CourseType.IN_PERSON, initialLocation))
                    .when()
                    .command(new UpdateLocation(courseId, newLocation))
                    .then()
                    .events(new LocationUpdated(courseId, newLocation));
        }
    }

    @Nested
    class WhenEnrollStudent {

        @Test
        void givenOnlineCourse_whenEnrollStudent_thenStudentEnrolled() {
            // given — online course with shared parent command handler
            String courseId = "course-online-4";
            String studentId = "student-1";

            // when / then — EnrollStudent is handled by the abstract parent CourseEntity
            fixture.given()
                    .event(new CourseCreated(courseId, "Event Sourcing Online", 10, CourseType.ONLINE, "https://platform.example.com"))
                    .when()
                    .command(new EnrollStudent(courseId, studentId))
                    .then()
                    .events(new StudentEnrolled(courseId, studentId));
        }
    }
}
