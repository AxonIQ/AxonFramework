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

package org.axonframework.examples.demo.multitenancy.university.write.opencourse;

import org.axonframework.examples.demo.multitenancy.university.UniversityTags;
import org.axonframework.examples.demo.multitenancy.university.events.CourseOpened;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.List;

/**
 * Handles opening a course, following the load-decide-append shape. The {@link State} is injected, having
 * been sourced from the events of the tenant resolved from the message, the handler decides against that
 * state, and the resulting event is appended through the {@link EventAppender}.
 * <p>
 * The handler names no tenant. Sourcing the injected {@link State} and appending the resulting event are
 * both routed to the tenant's own event store by the framework, so the same course identifier in two
 * tenants is two isolated event streams.
 */
class OpenCourseCommandHandler {

    /**
     * Opens the course, unless it is already open, so the command is idempotent.
     *
     * @param command       the command opening the course
     * @param state         the injected course state, sourced from the command's tenant's event store
     * @param eventAppender the appender the opening event is appended through
     */
    @CommandHandler
    void handle(OpenCourse command,
                @InjectEntity(idProperty = UniversityTags.COURSE_ID) State state,
                EventAppender eventAppender) {
        eventAppender.append(decide(command, state));
    }

    private List<CourseOpened> decide(OpenCourse command, State state) {
        if (state.open) {
            return List.of();
        }
        return List.of(new CourseOpened(command.courseId(), command.capacity()));
    }

    /**
     * The slice's own view of a course: only whether it has been opened, which is all this handler needs
     * to keep the command idempotent.
     */
    @EventSourcedEntity(tagKey = UniversityTags.COURSE_ID)
    static final class State {

        private boolean open;

        @EntityCreator
        State() {
            // A fresh course, evolved from its own tenant's events before the command handler sees it.
        }

        @EventSourcingHandler
        void evolve(CourseOpened event) {
            this.open = true;
        }
    }
}
