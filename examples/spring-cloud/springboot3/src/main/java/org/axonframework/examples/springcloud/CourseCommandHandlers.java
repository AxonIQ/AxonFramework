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

package org.axonframework.examples.springcloud;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handles the commands that create and rename courses in the {@link CourseCatalog}.
 */
@Component
@Profile("courses")
class CourseCommandHandlers {

    private static final Logger logger = LoggerFactory.getLogger(CourseCommandHandlers.class);

    private final CourseCatalog catalog;

    CourseCommandHandlers(CourseCatalog catalog) {
        this.catalog = catalog;
    }

    @CommandHandler
    Course create(CreateCourse command) {
        return catalog.create(command.courseId(), command.name());
    }

    @CommandHandler
    Course rename(RenameCourse command, EventAppender eventAppender) {
        if (catalog.find(command.courseId()) == null) {
            throw new IllegalArgumentException("Unknown course: " + command.courseId());
        }

        // Append before mutating the catalog: if the append fails, the catalog stays consistent with the event log.
        logger.debug("Appending CourseRenamed for course {}", command.courseId());
        eventAppender.append(List.of(new CourseRenamed(command.courseId(), command.name())));
        return catalog.rename(command.courseId(), command.name());
    }
}
