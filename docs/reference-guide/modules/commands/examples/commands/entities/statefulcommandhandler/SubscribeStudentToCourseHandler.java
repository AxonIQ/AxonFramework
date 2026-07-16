/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package commands.entities.statefulcommandhandler;

// tag::injecting-multiple-entities[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

@Component
public class SubscribeStudentToCourseHandler {

    private static final int MAX_COURSES_PER_STUDENT = 3;

    @CommandHandler
    void handle(
            SubscribeStudentToCourse command,
            @InjectEntity(idProperty = "courseId") Course course,     // <1>
            @InjectEntity(idProperty = "studentId") Student student,  // <2>
            EventAppender eventAppender
    ) {
        if (course.courseId() == null) {
            throw new IllegalStateException("Course does not exist");
        }
        if (student.studentId() == null) {
            throw new IllegalStateException("Student is not enrolled in the faculty");
        }
        if (student.subscribedCourses().size() >= MAX_COURSES_PER_STUDENT) {
            throw new IllegalStateException("Student is already subscribed to the maximum number of courses");
        }
        if (course.studentsSubscribed().size() >= course.capacity()) {
            throw new IllegalStateException("Course is fully booked");
        }
        eventAppender.append(new StudentSubscribedToCourse(command.courseId(), command.studentId()));
    }
}
// end::injecting-multiple-entities[]
