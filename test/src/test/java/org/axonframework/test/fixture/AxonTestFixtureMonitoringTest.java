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

package org.axonframework.test.fixture;

import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.test.fixture.AxonTestFixture.Customization;
import org.axonframework.test.fixture.AxonTestFixtureMonitoringTest.Domain.CourseAlreadyExists;
import org.axonframework.test.fixture.AxonTestFixtureMonitoringTest.Domain.CourseCreated;
import org.axonframework.test.fixture.AxonTestFixtureMonitoringTest.Domain.CreateCourse;
import org.axonframework.test.util.MessageMonitorReport;
import org.axonframework.test.util.RecordingMessageMonitor;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.test.fixture.AxonTestFixtureMonitoringTest.Domain.configurer;

/**
 * Configures MessageMonitors and verifies they have been correctly called when the sample domain ran.
 */
public class AxonTestFixtureMonitoringTest {

    private static final Logger logger = LoggerFactory.getLogger(AxonTestFixtureMonitoringTest.class);

    static class Domain {

        // configure application using given messageMonitor
        static EventSourcingConfigurer configurer(final MessageMonitorReport report, boolean isLoggingEnabled) {

            final var configurer = EventSourcingConfigurer.create();

            final var entity = EventSourcedEntityModule.autodetected(String.class,
                                                                  Domain.CourseCreatedCommandHandler.State.class);

            configurer.messaging(mc -> mc.registerMessageMonitor(c -> new RecordingMessageMonitor(report)));

            return configurer.registerEntity(entity)
                             .registerCommandHandlingModule(CommandHandlingModule.named("CreateCourse")
                                                                                 .commandHandlers()
                                                                                 .autodetectedCommandHandlingComponent(c -> new Domain.CourseCreatedCommandHandler()))
                    ;
        }

        public static final String COURSE_ID = "courseId";

        @Command
        public record CreateCourse(String courseId, String name) {
            // empty
        }

        @Event
        public record CourseCreated(@EventTag(key = COURSE_ID) String courseId, String name) {
            // empty
        }

        public record JustSomeAdditionalCourseCreated(String courseId, String name) {
            // empty
        }

        public static class CourseAlreadyExists extends RuntimeException {

            public CourseAlreadyExists(String courseId) {
                super("Course with courseId=%s already exists".formatted(courseId));
            }
        }

        static class CourseCreatedCommandHandler {

            @CommandHandler(commandName = "org.axonframework.test.fixture.CreateCourse")
            void handle(CreateCourse cmd, @InjectEntity(idProperty = COURSE_ID) CourseCreatedCommandHandler.State state,
                        EventAppender eventAppender) {
                eventAppender.append(state.decide(cmd));
            }

            @EventSourcedEntity(tagKey = COURSE_ID)
            static class State {

                private boolean created;

                @EntityCreator
                public State() {
                    this.created = false;
                }

                public List<Object> decide(CreateCourse cmd) {
                    if (created) {
                        throw new CourseAlreadyExists(cmd.courseId);
                    }
                    return List.of(new CourseCreated(cmd.courseId(), cmd.name()),
                                   new JustSomeAdditionalCourseCreated(cmd.courseId(), cmd.name()));
                }

                @EventSourcingHandler
                public State evolve(CourseCreated event) {
                    this.created = true;
                    return this;
                }
            }
        }
    }

    @Test
    void registeredCommandMessageMonitor() {
        var report = new MessageMonitorReport();
        final var courseId = UUID.randomUUID().toString();
        final var fixture = AxonTestFixture.with(
                configurer(report, false),
                Customization::asIntegrationTest
        );

        fixture
                .given()
                .noPriorActivity()
                .when()
                .command(new CreateCourse(courseId, "Math"))
                .then()
                .success()
                .events(new CourseCreated(courseId, "Math"),
                        new Domain.JustSomeAdditionalCourseCreated(courseId, "Math"))
                .and()
                .when()
                .command(new CreateCourse(courseId, "Math"))
                .then()
                .noEvents()
                .exception(CourseAlreadyExists.class);

        // running the sample produced reports via the registered message monitor
        assertThat(report).hasSize(4);
        assertThat(report.successReports()).hasSize(3);
        assertThat(report.failureReports()).hasSize(1);
        assertThat(report.failureReports().getFirst().message().payloadType()).isEqualTo(Domain.CreateCourse.class);
    }
}
