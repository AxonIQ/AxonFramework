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

package org.axonframework.integrationtests.testsuite.student;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.state.Course;
import org.axonframework.integrationtests.testsuite.student.state.Student;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.Objects;

/**
 * Sets up the basics for the testsuite of the Student/Mentor/Course model.
 * <p>
 * Can be customized by overriding the relevant methods. By default, uses a mix of different available options to
 * validate the different ways of setting up the event sourcing repository.
 * <p>
 * When using this test suite, be sure to invoke {@link #startApp()} when the test is all set.
 *
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 */
public abstract class AbstractStudentIT extends AbstractIT {

    protected static final GenericCommandResultMessage SUCCESSFUL_COMMAND_RESULT =
            new GenericCommandResultMessage(new MessageType("empty"), "successful");

    protected UnitOfWorkFactory unitOfWorkFactory;

    private EventSourcedEntityModule<String, Course> courseEntity;
    private EventSourcedEntityModule<String, Student> studentEntity;

    @BeforeEach
    protected void prepareModule() {
        studentEntity = EventSourcedEntityModule
                .declarative(String.class, Student.class)
                .messagingModel((c, b) -> b
                        .entityEvolver(studentEvolver(c))
                        .build())
                .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(Student::new))
                .criteriaResolver(this::studentCriteriaResolver)
                .build();

        courseEntity = EventSourcedEntityModule
                .declarative(String.class, Course.class)
                .messagingModel((c, b) -> b
                        .entityEvolver(courseEvolver(c))
                        .build())
                .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(Course::new))
                .criteriaResolver(this::courseCriteriaResolver)
                .build();
    }

    @Override
    protected ApplicationConfigurer applicationConfigurer() {
        var configurer = EventSourcingConfigurer.create()
                                                .componentRegistry(cr -> cr.registerModule(studentEntity))
                                                .componentRegistry(cr -> cr.registerModule(courseEntity));
        return testSuiteConfigurer(configurer);
    }

    /**
     * Starts the Axon Framework application.
     */
    @Override
    protected void startApp() {
        super.startApp();
        unitOfWorkFactory = startedConfiguration.getComponent(UnitOfWorkFactory.class);
    }

    /**
     * Allows for further configuration of the {@link EventSourcingConfigurer} used in the test suite.
     * <p>
     * This method can be overridden by subclasses to add additional configuration.
     *
     * @param configurer The {@link EventSourcingConfigurer} to configure.
     * @return The configured {@link EventSourcingConfigurer}.
     */
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        return configurer;
    }

    /**
     * Returns the {@link EntityEvolver} for the {@link Course} model. Defaults to manually calling the event sourcing
     * handlers on the model.
     */
    protected EntityEvolver<Course> courseEvolver(@NonNull Configuration config) {
        return (course, event, context) -> {
            if (event.type().name().equals(StudentEnrolledEvent.class.getName())) {
                // Convert the payload to the expected type
                StudentEnrolledEvent convert = event.payloadAs(StudentEnrolledEvent.class);
                Objects.requireNonNull(convert, "The converted payload must not be null.");
                course.handle(convert);
            }
            return course;
        };
    }

    /**
     * Returns the {@link CriteriaResolver} for the {@link Course} model. Defaults to a criteria that matches any event
     * with the tag "Course" and the given model id.
     */
    protected CriteriaResolver<String> courseCriteriaResolver(Configuration config) {
        return (courseId, ctx) -> EventCriteria.havingTags(new Tag("Course", courseId));
    }

    /**
     * Returns the {@link CriteriaResolver} for the {@link Student} model. Defaults to a criteria that matches any event
     * with the tag "Student" and the given model id.
     */
    protected CriteriaResolver<String> studentCriteriaResolver(Configuration config) {
        return (studentId, ctx) -> EventCriteria.havingTags(new Tag("Student", studentId));
    }

    /**
     * Returns the {@link EntityEvolver} for the {@link Student} model. Defaults to using the
     * {@link AnnotationBasedEntityEvolvingComponent} to use the annotation placed.
     */
    protected EntityEvolver<Student> studentEvolver(Configuration config) {
        return new AnnotationBasedEntityEvolvingComponent<>(
                Student.class,
                config.getComponent(EventConverter.class),
                config.getComponent(MessageTypeResolver.class)
        );
    }

    protected <T> void sendCommand(T payload) {
        commandGateway.sendAndWait(payload);
    }

    protected <T, R> R sendCommand(T payload, Class<R> expectedResultType) {
        return commandGateway.sendAndWait(payload, expectedResultType);
    }

    protected StudentEnrolledEvent studentEnrolledToCourse(String studentId, String courseId) {
        StudentEnrolledEvent event = new StudentEnrolledEvent(studentId, courseId);
        storeEvent(StudentEnrolledEvent.class, event);
        return event;
    }

    protected <T> void storeEvent(Class<T> clazz, T payload) {
        storeEvent(clazz, payload, null);
    }

    protected <T> void storeEvent(Class<T> clazz, T payload, Metadata metadata) {
        UnitOfWork uow = unitOfWorkFactory.create();
        var eventMessage = new GenericEventMessage(
                new MessageType(clazz),
                payload,
                metadata == null ? Metadata.emptyInstance() : metadata
        );
        uow.runOnInvocation(context -> context.component(EventGateway.class).publish(context, eventMessage));
        uow.execute().join();
    }
}
