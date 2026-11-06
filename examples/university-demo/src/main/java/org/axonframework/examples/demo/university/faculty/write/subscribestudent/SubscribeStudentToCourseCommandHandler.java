package org.axonframework.examples.demo.university.faculty.write.subscribestudent;

import org.axonframework.examples.demo.university.faculty.FacultyTags;
import org.axonframework.examples.demo.university.faculty.Ids;
import org.axonframework.examples.demo.university.faculty.events.*;
import org.axonframework.examples.demo.university.shared.ids.CourseId;
import org.axonframework.examples.demo.university.shared.ids.StudentId;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.List;

class SubscribeStudentToCourseCommandHandler {

    private static final int MAX_COURSES_PER_STUDENT = 3;

    @CommandHandler
    void handle(
            SubscribeStudentToCourse command,
            @InjectEntity State state,
            EventAppender eventAppender
    ) {
        var events = decide(command, state);
        eventAppender.append(events);
    }

    private List<StudentSubscribedToCourse> decide(SubscribeStudentToCourse command, State state) {
        assertStudentEnrolledFaculty(state);
        assertStudentNotSubscribedToTooManyCourses(state);
        assertCourseExists(state);
        assertEnoughVacantSpotsInCourse(state);
        assertStudentNotAlreadySubscribed(state);

        return List.of(new StudentSubscribedToCourse(Ids.FACULTY_ID, command.studentId(), command.courseId()));
    }

    private void assertStudentEnrolledFaculty(State state) {
        var studentId = state.studentId;
        if (studentId == null) {
            throw new RuntimeException("Student with given id never enrolled the faculty");
        }
    }

    private void assertStudentNotSubscribedToTooManyCourses(State state) {
        var noOfCoursesStudentSubscribed = state.noOfCoursesStudentSubscribed;
        if (noOfCoursesStudentSubscribed >= MAX_COURSES_PER_STUDENT) {
            throw new RuntimeException("Student subscribed to too many courses");
        }
    }

    private void assertEnoughVacantSpotsInCourse(State state) {
        var noOfStudentsSubscribedToCourse = state.noOfStudentsSubscribedToCourse;
        var courseCapacity = state.courseCapacity;
        if (noOfStudentsSubscribedToCourse >= courseCapacity) {
            throw new RuntimeException("Course is fully booked");
        }
    }

    private void assertStudentNotAlreadySubscribed(State state) {
        var alreadySubscribed = state.alreadySubscribed;
        if (alreadySubscribed) {
            throw new RuntimeException("Student already subscribed to this course");
        }
    }

    private void assertCourseExists(State state) {
        var courseId = state.courseId;
        if (courseId == null) {
            throw new RuntimeException("Course with given id does not exist");
        }
    }

    @EventSourcedEntity
    static class State {

        private CourseId courseId;
        private int courseCapacity = 0;
        private int noOfStudentsSubscribedToCourse = 0;

        private StudentId studentId;
        private int noOfCoursesStudentSubscribed = 0;
        private boolean alreadySubscribed = false;

        @EntityCreator
        public State() {
        }

        @EventSourcingHandler
        void evolve(CourseCreated event) {
            this.courseId = event.courseId();
            this.courseCapacity = event.capacity();
        }

        @EventSourcingHandler
        void evolve(CourseCapacityChanged event) {
            this.courseCapacity = event.capacity();
        }

        @EventSourcingHandler
        void evolve(StudentEnrolledInFaculty event) {
            this.studentId = event.studentId();
        }

        @EventSourcingHandler
        void evolve(StudentSubscribedToCourse event) {
            var subscribingStudentId = event.studentId();
            var subscribedCourseId = event.courseId();
            if (subscribedCourseId.equals(courseId)) {
                noOfStudentsSubscribedToCourse++;
            }
            if (subscribingStudentId.equals(studentId)) {
                noOfCoursesStudentSubscribed++;
            }
            if (subscribingStudentId.equals(studentId) && subscribedCourseId.equals(courseId)) {
                alreadySubscribed = true;
            }
        }

        @EventSourcingHandler
        void evolve(StudentUnsubscribedFromCourse event) {
            var subscribingStudentId = event.studentId();
            var subscribedCourseId = event.courseId();
            if (subscribedCourseId.equals(courseId)) {
                noOfStudentsSubscribedToCourse--;
            }
            if (subscribingStudentId.equals(studentId)) {
                noOfCoursesStudentSubscribed--;
            }
            if (subscribingStudentId.equals(studentId) && subscribedCourseId.equals(courseId)) {
                alreadySubscribed = false;
            }
        }

        @EventCriteriaBuilder
        private static EventCriteria resolveCriteria(SubscriptionId id) {
            var courseId = id.courseId().toString();
            var studentId = id.studentId().toString();
            return EventCriteria.either(
                    EventCriteria
                            .havingTags(Tag.of(FacultyTags.COURSE_ID, courseId))
                            .andBeingOneOfTypes(
                                    CourseCreated.class.getName(),
                                    CourseCapacityChanged.class.getName(),
                                    StudentSubscribedToCourse.class.getName(),
                                    StudentUnsubscribedFromCourse.class.getName()
                            ),
                    EventCriteria
                            .havingTags(Tag.of(FacultyTags.STUDENT_ID, studentId))
                            .andBeingOneOfTypes(
                                    StudentEnrolledInFaculty.class.getName(),
                                    StudentSubscribedToCourse.class.getName(),
                                    StudentUnsubscribedFromCourse.class.getName()
                            )
            );
        }
    }
}
