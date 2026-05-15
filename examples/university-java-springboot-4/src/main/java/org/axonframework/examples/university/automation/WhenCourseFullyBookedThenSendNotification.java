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

package org.axonframework.examples.university.automation;

import lombok.extern.slf4j.Slf4j;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.university.event.CourseCapacityChanged;
import org.axonframework.examples.university.event.CourseCreated;
import org.axonframework.examples.university.event.CourseFullyBookedNotificationSent;
import org.axonframework.examples.university.event.StudentSubscribedToCourse;
import org.axonframework.examples.university.event.StudentUnsubscribedFromCourse;
import org.axonframework.examples.university.shared.CourseId;
import org.axonframework.examples.university.shared.FacultyTags;
import org.axonframework.examples.university.shared.notification.NotificationService;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * This class represents the automation of sending a Command some condition is met. It's a Stateful Event Handler that
 * reacts on multiple events and tracks the state (left spots) of courses.
 * <p>
 * The implementation uses event soured entity to track the state of courses and their capacities. When a course is
 * determined to be fully booked, a notification is sent to the appropriate recipient.
 * <p>
 * The functionality includes: - Managing the state of courses (availability and subscription levels). - Reacting to
 * events such as course capacity updates, student subscriptions, and un-subscriptions. - Sending notification if
 * necessary.
 */
@Component
@Slf4j
public class WhenCourseFullyBookedThenSendNotification {

    @EventHandler
    CompletableFuture<?> react(
            StudentSubscribedToCourse event,
            CommandDispatcher commandDispatcher,
            ProcessingContext context
    ) {
        var state = context.component(StateManager.class).loadEntity(State.class, event.courseId(), context).join();
        return sendNotificationIfCourseFullyBooked(state, commandDispatcher);
    }

    @EventHandler
    CompletableFuture<?> react(
            CourseCapacityChanged event,
            CommandDispatcher commandDispatcher,
            ProcessingContext context
    ) {
        var state = context.component(StateManager.class).loadEntity(State.class, event.courseId(), context).join();
        return sendNotificationIfCourseFullyBooked(state, commandDispatcher);
    }

    private CompletableFuture<?> sendNotificationIfCourseFullyBooked(
            State state,
            CommandDispatcher commandDispatcher
    ) {
        var automationState = state != null ? state : new State();
        var courseFullyBooked = automationState.course != null && automationState.course.isFullyBooked();
        var shouldNotify = courseFullyBooked && !automationState.notified();
        if (!shouldNotify) {
            return CompletableFuture.completedFuture(null);
        }
        return commandDispatcher.send(new SendCourseFullyBookedNotification(automationState.course.courseId()),
                                      Object.class);
    }


    @CommandHandler
    void decide(
            SendCourseFullyBookedNotification command,
            @InjectEntity State state,
            ProcessingContext context
    ) {
        var canNotify = state != null && !state.notified();
        if (canNotify) {

            var message = String.format("Course %s is fully booked", command.courseId());
            var notification = new NotificationService.Notification("admin", message);
            context.component(NotificationService.class).sendNotification(notification);

            var eventAppender = EventAppender.forContext(context);
            eventAppender.append(new CourseFullyBookedNotificationSent(command.courseId()));
        }
    }

    @EventSourced(idType = CourseId.class, tagKey = FacultyTags.COURSE_ID)
    record State(Course course, boolean notified) {

        record Course (CourseId courseId,int capacity, int students){

            Course capacity ( int newCapacity){
                return new Course(this.courseId, newCapacity, this.students);
            }

            Course studentSubscribed () {
                return new Course(this.courseId, this.capacity, this.students + 1);
            }

            Course studentUnsubscribed () {
                return new Course(this.courseId, this.capacity, this.students - 1);
            }

            boolean isFullyBooked () {
                return students >= capacity;
            }
        }

        @EntityCreator
                State() {
            this(null, false);
        }

        @EventSourcingHandler
        State evolve (CourseCreated event){
            return new State(new Course(event.courseId(), event.capacity(), 0), isNotified());
        }

        @EventSourcingHandler
        State evolve (CourseCapacityChanged event){
            return new State(course.capacity(event.capacity()), isNotified());
        }

        @EventSourcingHandler
        State evolve (StudentSubscribedToCourse event){
            return new State(course.studentSubscribed(), isNotified());
        }

        @EventSourcingHandler
        State evolve (StudentUnsubscribedFromCourse event){
            return new State(course.studentUnsubscribed(), isNotified());
        }

        @EventSourcingHandler
        State evolve (CourseFullyBookedNotificationSent event){
            return new State(course, true);
        }

        private boolean isNotified () {
            if (course != null && course.isFullyBooked()) {
                return notified;
            }
            return false;
        }
    }
}
