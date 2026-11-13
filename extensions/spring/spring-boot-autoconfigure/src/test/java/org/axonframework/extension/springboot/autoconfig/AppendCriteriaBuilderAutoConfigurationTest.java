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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.configuration.Module;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating that {@link AppendCriteriaBuilder} methods apply in a Spring Boot application, to both a
 * command-handling bean and an event-sourced entity, including when Spring composes the handler enhancers from
 * {@link HandlerEnhancerDefinition} beans.
 *
 * @author Mateusz Nowak
 */
class AppendCriteriaBuilderAutoConfigurationTest {

    private static final List<Object> BUILT_FOR = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        BUILT_FOR.clear();
    }

    @Test
    void buildersApplyToCommandHandlingBeansAndEventSourcedEntities() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0")
                .run(context -> {
                    // when
                    CommandGateway commandGateway = context.getBean(CommandGateway.class);
                    commandGateway.sendAndWait(new RegisterAttendee("attendee-1"));
                    commandGateway.sendAndWait(new CreateCourse("course-1"));

                    // then
                    assertThat(BUILT_FOR).containsExactly(new RegisterAttendee("attendee-1"),
                                                          new CreateCourse("course-1"));
                });
    }

    @Test
    void buildersApplyWhenSpringComposesTheHandlerEnhancersFromBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class, HandlerEnhancerDefinitionBeanContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0")
                .run(context -> {
                    // given a HandlerEnhancerDefinition bean, which makes Spring compose the enhancers
                    assertThat(context).hasBean("noOpHandlerEnhancerDefinition");

                    // when
                    CommandGateway commandGateway = context.getBean(CommandGateway.class);
                    commandGateway.sendAndWait(new RegisterAttendee("attendee-1"));
                    commandGateway.sendAndWait(new CreateCourse("course-1"));

                    // then
                    assertThat(BUILT_FOR).containsExactly(new RegisterAttendee("attendee-1"),
                                                          new CreateCourse("course-1"));
                });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class TestContext {

        @Bean
        public AttendeeCommandHandler attendeeCommandHandler() {
            return new AttendeeCommandHandler();
        }

        @Bean
        public Module courseEntityModule() {
            return EventSourcedEntityModule.autodetected(String.class, Course.class);
        }
    }

    private static class HandlerEnhancerDefinitionBeanContext {

        @Bean
        public HandlerEnhancerDefinition noOpHandlerEnhancerDefinition() {
            return new HandlerEnhancerDefinition() {
                @Override
                public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
                    return original;
                }
            };
        }
    }

    @SuppressWarnings("unused")
    private static class AttendeeCommandHandler {

        @CommandHandler
        public void handle(RegisterAttendee command, EventAppender appender) {
            appender.append(new AttendeeRegistered(command.attendeeId()));
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(RegisterAttendee command, EventCriteria sourcingCriteria) {
            BUILT_FOR.add(command);
            return sourcingCriteria;
        }
    }

    @SuppressWarnings("unused")
    @EventSourcedEntity(tagKey = "courseId")
    static class Course {

        @EntityCreator
        public Course() {
        }

        @CommandHandler
        public static void handle(CreateCourse command, EventAppender appender) {
            appender.append(new CourseCreated(command.courseId()));
        }

        @EventSourcingHandler
        public void on(CourseCreated event) {
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(CreateCourse command, EventCriteria sourcingCriteria) {
            BUILT_FOR.add(command);
            return sourcingCriteria;
        }
    }

    record RegisterAttendee(String attendeeId) {

    }

    record AttendeeRegistered(String attendeeId) {

    }

    record CreateCourse(String courseId) {

    }

    record CourseCreated(String courseId) {

    }
}
