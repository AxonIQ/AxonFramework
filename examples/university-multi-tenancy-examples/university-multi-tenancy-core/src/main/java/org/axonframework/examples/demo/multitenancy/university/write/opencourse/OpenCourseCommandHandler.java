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

import io.axoniq.framework.messaging.multitenancy.annotation.TenantScoped;
import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.examples.demo.multitenancy.university.UniversityTags;
import org.axonframework.examples.demo.multitenancy.university.events.CourseOpened;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.ReadModelWrites;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.List;
import java.util.Objects;

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

    private final DemoBacking backing;

    /**
     * Constructs a handler that records the course's capacity in the read model itself only when the given
     * {@code backing} has no projection to do it, mirroring how the enroll-student handler fills the rest of
     * the read model on that same backing.
     *
     * @param backing what backs this run
     */
    OpenCourseCommandHandler(DemoBacking backing) {
        this.backing = Objects.requireNonNull(backing, "The backing must not be null");
    }

    /**
     * Opens the course, unless it is already open, so the command is idempotent.
     *
     * @param command               the command opening the course
     * @param state                 the injected course state, sourced from the command's tenant's event store
     * @param eventAppender         the appender the opening event is appended through
     * @param courseStatisticsStore the injected course-statistics store of the command's tenant
     */
    @CommandHandler
    void handle(OpenCourse command,
                @InjectEntity(idProperty = UniversityTags.COURSE_ID) State state,
                EventAppender eventAppender,
                @TenantScoped CourseStatisticsStore courseStatisticsStore) {
        List<CourseOpened> events = decide(command, state);
        eventAppender.append(events);
        if (!backing.projectsReadModel() && !events.isEmpty()) {
            ReadModelWrites.recordCourseOpened(courseStatisticsStore, command.courseId(), command.capacity());
        }
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
