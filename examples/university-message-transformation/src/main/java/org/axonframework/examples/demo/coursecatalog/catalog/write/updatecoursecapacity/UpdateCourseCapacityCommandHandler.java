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

package org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CourseCapacityChanged;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Updates a published course's capacity range. The state is reconstructed from the
 * full course history; historic v1/v2 {@code CoursePublished} events flow through
 * the chain and reach {@code evolve(CoursePublished)} in the v3 shape transparently.
 */
class UpdateCourseCapacityCommandHandler {

    @CommandHandler
    void handle(
            UpdateCourseCapacity command,
            @InjectEntity(idProperty = CourseCatalogTags.COURSE_ID) State state,
            EventAppender eventAppender
    ) {
        eventAppender.append(decide(command, state));
    }

    private List<CourseCapacityChanged> decide(UpdateCourseCapacity command, State state) {
        assertCoursePublished(state);
        assertNewRangeFitsCurrentEnrolments(state, command);
        if (command.newRange().equals(state.range)) {
            return List.of();
        }
        return List.of(new CourseCapacityChanged(command.courseId(), command.newRange()));
    }

    private static void assertCoursePublished(State state) {
        if (!state.published) {
            throw new IllegalStateException("Course with given id does not exist");
        }
    }

    private static void assertNewRangeFitsCurrentEnrolments(State state, UpdateCourseCapacity command) {
        // Lowering the maximum below the number of already-enrolled students would leave
        // the course in an inconsistent state. The minimum side is unconstrained by
        // current enrolment.
        if (state.enrolments > command.newRange().max()) {
            throw new IllegalStateException(
                    "New maximum capacity " + command.newRange().max()
                            + " is below current enrolment count " + state.enrolments);
        }
    }

    @EventSourcedEntity(tagKey = CourseCatalogTags.COURSE_ID)
    static final class State {

        private boolean published;
        @Nullable
        private CapacityRange range;
        private int enrolments;

        @EntityCreator
        State() {
        }

        @EventSourcingHandler
        void evolve(CoursePublished event) {
            this.published = true;
            this.range = event.range();
        }

        @EventSourcingHandler
        void evolve(CourseCapacityChanged event) {
            this.range = event.newRange();
        }

        @EventSourcingHandler
        void evolve(StudentEnrolledInCourse event) {
            this.enrolments++;
        }
    }
}
