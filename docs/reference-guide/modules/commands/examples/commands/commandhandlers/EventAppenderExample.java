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
package commands.commandhandlers;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class EventAppenderExample {

    // tag::publishing-events-with-eventappender[]
    @CommandHandler
    public static CourseId handle(CreateCourse command, EventAppender eventAppender) { // <1>
        if (command.capacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        eventAppender.append(new CourseCreated(Ids.FACULTY_ID, command.courseId(), command.name(), command.capacity())); // <2>
        return command.courseId();
    }

    @CommandHandler
    public void handle(RenameCourse command, EventAppender eventAppender) {
        eventAppender.append( // <3>
                new CourseRenamed(Ids.FACULTY_ID, command.courseId(), command.name())
        );
    }
    // end::publishing-events-with-eventappender[]
}
