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

package org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.List;

class PublishCourseCommandHandler {

    @CommandHandler
    void handle(
            PublishCourse command,
            @InjectEntity(idProperty = CourseCatalogTags.COURSE_ID) State state,
            EventAppender eventAppender
    ) {
        eventAppender.append(decide(command, state));
    }

    private List<CoursePublished> decide(PublishCourse command, State state) {
        // Idempotency: re-publishing a course is a no-op rather than an error.
        if (state.published) {
            return List.of();
        }
        return List.of(new CoursePublished(Ids.CATALOG_ID, command.courseId(), command.name(), command.range()));
    }

    @EventSourcedEntity(tagKey = CourseCatalogTags.COURSE_ID)
    static final class State {

        private boolean published;

        @EntityCreator
        State() {
            this.published = false;
        }

        @EventSourcingHandler
        void evolve(CoursePublished event) {
            this.published = true;
        }
    }
}
