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

import commands.entities.statefulcommandhandler.CourseCommands.RenameCourse;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

// tag::vertical-slice-handler[]
@Component
public class RenameCourseHandler {

    @CommandHandler
    void handle(RenameCourse command,
                @InjectEntity Course course,
                EventAppender eventAppender) {
        if (course.courseId() == null) {
            throw new IllegalStateException("Course does not exist");
        }
        if (!command.name().equals(course.name())) {
            eventAppender.append(new CourseRenamed(command.courseId(), command.name()));
        }
    }
}
// end::vertical-slice-handler[]
