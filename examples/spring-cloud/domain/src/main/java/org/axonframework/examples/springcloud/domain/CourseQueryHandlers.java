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

package org.axonframework.examples.springcloud.domain;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Handles the {@link FindCourse} query and keeps its subscription queries updated whenever a course is renamed.
 */
@Component
@Profile("courses")
class CourseQueryHandlers {

    private static final Logger logger = LoggerFactory.getLogger(CourseQueryHandlers.class);

    private final CourseCatalog catalog;

    CourseQueryHandlers(CourseCatalog catalog) {
        this.catalog = catalog;
    }

    @EventHandler
    void handle(CourseRenamed event, QueryUpdateEmitter emitter) {
        Course renamed = catalog.find(event.courseId());
        logger.debug("Emitting FindCourse update for course {}: {}", event.courseId(), renamed);
        emitter.emit(FindCourse.class, query -> event.courseId().equals(query.courseId()), () -> renamed);
    }

    @QueryHandler
    Course find(FindCourse query) {
        Course course = catalog.find(query.courseId());
        if (course == null) {
            throw new IllegalArgumentException("Unknown course: " + query.courseId());
        }
        return course;
    }
}
