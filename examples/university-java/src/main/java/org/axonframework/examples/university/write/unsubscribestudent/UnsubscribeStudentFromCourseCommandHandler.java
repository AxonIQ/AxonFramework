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

package org.axonframework.examples.university.write.unsubscribestudent;

import org.jspecify.annotations.NonNull;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.university.event.StudentSubscribedToCourse;
import org.axonframework.examples.university.event.StudentUnsubscribedFromCourse;
import org.axonframework.examples.university.shared.FacultyTags;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.List;

class UnsubscribeStudentFromCourseCommandHandler {

    @CommandHandler
    void handle(
            UnsubscribeStudentFromCourse command,
            @InjectEntity(idResolver = SubscriptionIdResolver.class) State state,
            EventAppender eventAppender
    ) {
        var events = decide(command, state);
        eventAppender.append(events);
    }

    private List<StudentUnsubscribedFromCourse> decide(UnsubscribeStudentFromCourse command, State state) {
        return state.subscribed
                ? List.of(new StudentUnsubscribedFromCourse(command.studentId(), command.courseId()))
                : List.of();
    }

    @EventSourcedEntity
    static final class State {

        boolean subscribed = false;

        @EntityCreator
        public State() {
        }

        @EventSourcingHandler
        void evolve(StudentSubscribedToCourse event) {
            this.subscribed = true;
        }

        @EventSourcingHandler
        void evolve(StudentUnsubscribedFromCourse event) {
            this.subscribed = false;
        }

        @EventCriteriaBuilder
        private static EventCriteria resolveCriteria(SubscriptionId id) {
            var courseId = id.courseId().toString();
            var studentId = id.studentId();
            return EventCriteria
                    .havingTags(Tag.of(FacultyTags.COURSE_ID, courseId), Tag.of(FacultyTags.STUDENT_ID, studentId))
                    .andBeingOneOfTypes(
                            StudentSubscribedToCourse.class.getName(),
                            StudentUnsubscribedFromCourse.class.getName()
                    );
        }
    }

    private static class SubscriptionIdResolver implements EntityIdResolver<SubscriptionId> {

        @Override
        public @NonNull SubscriptionId resolve(@NonNull Message command, @NonNull ProcessingContext context) {
            var converter = context.component(MessageConverter.class);
            UnsubscribeStudentFromCourse payload = command.payloadAs(UnsubscribeStudentFromCourse.class, converter);
            if (payload == null) {
                throw new IllegalArgumentException("Can not resolve SubscriptionId from command");
            }
            return new SubscriptionId(payload.courseId(), payload.studentId());
        }
    }
}
