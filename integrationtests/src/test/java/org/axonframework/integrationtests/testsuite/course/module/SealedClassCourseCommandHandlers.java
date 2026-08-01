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

package org.axonframework.integrationtests.testsuite.course.module;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.integrationtests.testsuite.course.commands.CreateCourse;
import org.axonframework.integrationtests.testsuite.course.commands.PublishCourse;
import org.axonframework.integrationtests.testsuite.course.events.CourseCreated;
import org.axonframework.integrationtests.testsuite.course.events.CoursePublished;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

public class SealedClassCourseCommandHandlers {

    @CommandHandler
    public void handle(CreateCourse cmd, @InjectEntity @Nullable CourseState state, EventAppender appender) {
        appender.append(new CourseCreated(cmd.courseId()));
    }

    @CommandHandler
    public void handle(PublishCourse cmd, @InjectEntity CourseState state, EventAppender appender) {
        appender.append(new CoursePublished(cmd.courseId()));
    }

    @EventSourcedEntity(tagKey = "Course")
    public sealed interface CourseState {

        @EntityCreator
        static CourseState create() {
            return new InitialCourse();
        }

        record CreatedCourse(String courseId) implements CourseState {

            @EventSourcingHandler
            private PublishedCourse on(CoursePublished event) {
                return new PublishedCourse(event.courseId());
            }
        }

        record InitialCourse() implements CourseState {

            @EventSourcingHandler
            private CreatedCourse on(CourseCreated event) {
                return new CreatedCourse(event.courseId());
            }
        }

        record PublishedCourse(String courseId) implements CourseState {

        }
    }
}
