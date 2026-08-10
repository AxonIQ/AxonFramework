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

package org.axonframework.integrationtests.testsuite.coursecreation;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.integrationtests.testsuite.coursecreation.commands.CreateCourse;
import org.axonframework.integrationtests.testsuite.coursecreation.module.CourseCreationCommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test suite proving that {@link org.axonframework.eventsourcing.eventstore.EventStoreAppender
 * EventStoreAppender} can assert a historical uniqueness constraint from a stateless command handler, one that
 * never sources or loads any entity at all.
 * <p>
 * {@link CourseCreationCommandHandler} has no entity, no {@code @EventSourcedEntity}, and no prior state of any
 * kind. Its only guard against a duplicate course id is the append condition it registers, checked from
 * {@link org.axonframework.eventsourcing.eventstore.ConsistencyMarker#ORIGIN ORIGIN} against the entire event
 * store history rather than anything this transaction sourced.
 */
public abstract class AbstractCourseCreationIT extends AbstractIT {

    @BeforeEach
    void doStartApp() {
        startApp();
    }

    @Override
    protected ApplicationConfigurer applicationConfigurer() {
        var courseCreationModule = CommandHandlingModule
                .named("CourseCreation")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(config -> new CourseCreationCommandHandler());
        return EventSourcingConfigurer.create()
                                      .registerCommandHandlingModule(courseCreationModule);
    }

    @Test
    void creatingACourseSucceedsWhenNoCourseWithThatIdExistsYet() {
        assertThatCode(() -> createCourse("course-1", "Domain-Driven Design"))
                .doesNotThrowAnyException();
    }

    @Test
    void creatingTheSameCourseTwiceIsRejectedFromOriginEvenThoughNothingWasSourced() {
        createCourse("course-2", "Domain-Driven Design");

        assertThatThrownBy(() -> createCourse("course-2", "Domain-Driven Design (retry)"))
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
    }

    @Test
    void creatingDifferentCoursesNeverConflictsWithEachOther() {
        createCourse("course-3", "Domain-Driven Design");

        assertThatCode(() -> createCourse("course-4", "Command-Query Responsibility Segregation"))
                .doesNotThrowAnyException();
    }

    private void createCourse(String courseId, String name) {
        commandGateway.send(new CreateCourse(courseId, name)).getResultMessage().join();
    }
}
