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
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test case validating that a {@link HandlerEnhancerDefinition} bean should be applied consistently to both an
 * autodetected handling components and an {@link EventSourcedEntity}, within the same Spring context.
 *
 * @author Steven van Beelen
 */
class HandlerEnhancerDefinitionEventSourcedEntityConsistencyTest {

    private static final Set<Class<?>> WRAPPED_DECLARING_CLASSES = new HashSet<>();

    @BeforeEach
    void setUp() {
        WRAPPED_DECLARING_CLASSES.clear();
    }

    @Test
    void handlerEnhancerDefinitionWrapsBothAutodetectedComponentAndEventSourcedEntity() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(CustomHandlerEnhancerDefinition.class);

                    context.getBean(CommandGateway.class)
                           .sendAndWait(new CreateCourse("course-1", "Migrating to Axon 5"));

                    assertThat(WRAPPED_DECLARING_CLASSES)
                            .as("both the autodetected component and the event-sourced entity should be wrapped")
                            .contains(MyEventHandlingComponent.class, Course.class);
                });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class TestContext {

        @Bean
        public HandlerEnhancerDefinition customHandlerEnhancerDefinition() {
            return new CustomHandlerEnhancerDefinition();
        }

        @Bean
        public MyEventHandlingComponent myEventHandlingComponent() {
            return new MyEventHandlingComponent();
        }

        @Bean
        public Module courseEntityModule() {
            return EventSourcedEntityModule.autodetected(String.class, Course.class);
        }
    }

    private static class CustomHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

        @Override
        public @NonNull <T> MessageHandlingMember<T> wrapHandler(@NonNull MessageHandlingMember<T> original) {
            WRAPPED_DECLARING_CLASSES.add(original.declaringClass());
            return original;
        }
    }

    @SuppressWarnings("unused")
    private static class MyEventHandlingComponent {

        @EventHandler
        public void on(Object someEvent) {

        }
    }

    @SuppressWarnings("unused")
    @EventSourcedEntity(tagKey = "courseId")
    static class Course {

        @EntityCreator
        public Course() {
            // No-arg constructor
        }

        @CommandHandler
        public static void handle(CreateCourse command, EventAppender appender) {
            appender.append(new CourseCreated(command.courseId(), command.name()));
        }

        @EventSourcingHandler
        public void on(CourseCreated event) {
            // No-op: this test only checks handler creation/wrapping, not state evolution.
        }
    }

    record CreateCourse(String courseId, String name) {

    }

    record CourseCreated(String courseId, String name) {

    }
}
