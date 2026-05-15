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

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.configuration.StateBasedEntityModule;
import org.axonframework.modelling.repository.InMemoryRepository;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

/**
 * Test class validating the declarative {@link PooledStreamingEventProcessor} used as a projector for a read model
 * based on {@link StateBasedEntityModule}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public abstract class EventProcessingAnnotatedStateBasedPooledStreamingIT extends AbstractStudentIT {

    @Test
    void whenStudentEnrolledThenUpdateReadModel() {
        // given
        startApp();

        // when
        var studentId = UUID.randomUUID().toString();
        studentEnrolledToCourse(studentId, "my-courseId-1");
        studentEnrolledToCourse(studentId, "my-courseId-2");
        studentEnrolledToCourse(studentId, "my-courseId-3");

        // then
        verifyReadModelState(studentId, state -> {
            assertThat(state).isNotNull();
            assertThat(state.courses).containsExactly("my-courseId-1", "my-courseId-2", "my-courseId-3");
        });
    }

    record StudentCoursesReadModel(String studentId, List<String> courses) {

        StudentCoursesReadModel(String studentId) {
            this(studentId, new ArrayList<>());
        }

        StudentCoursesReadModel evolve(StudentEnrolledEvent event) {
            var updatedCourses = new ArrayList<>(courses);
            updatedCourses.add(event.courseId());
            return new StudentCoursesReadModel(studentId, updatedCourses);
        }
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        var studentCoursesRepository = new InMemoryRepository<>(String.class, StudentCoursesReadModel.class);
        StateBasedEntityModule<String, StudentCoursesReadModel> studentCoursesEntity =
                StateBasedEntityModule.declarative(String.class, StudentCoursesReadModel.class)
                                      .repository(cfg -> studentCoursesRepository)
                                      .messagingModel(
                                              (cfg, builder) -> builder.entityEvolver(readModelEvolver())
                                                                       .build()
                                      )
                                      .entityIdResolver(cfg -> (message, context) -> {
                                          var payload = message.payloadAs(
                                                  StudentEnrolledEvent.class,
                                                  context.component(EventConverter.class)
                                          );
                                          return payload.studentId();
                                      });
        configurer.componentRegistry(cr -> cr.registerModule(studentCoursesEntity));

        var studentRegisteredCoursesProcessor = EventProcessorModule
                .pooledStreaming("student-courses-readmodel-processor")
                .eventHandlingComponents(
                        components -> components.declarative("studentCoursesProjector", cfg -> studentCoursesProjector())
                )
                .notCustomized();
        return configurer.messaging(
                messaging -> messaging.eventProcessing(
                        ep -> ep.pooledStreaming(
                                ps -> ps.processor(studentRegisteredCoursesProcessor)
                        )
                )
        );
    }

    static @NonNull EventHandlingComponent studentCoursesProjector() {
        SimpleEventHandlingComponent studentCoursesProjector =
                SimpleEventHandlingComponent.create("studentCoursesProjector", SequentialPolicy.INSTANCE);
        studentCoursesProjector.subscribe(
                new QualifiedName(StudentEnrolledEvent.class),
                (event, context) -> {
                    var studentEnrolled = requireNonNull(event.payloadAs(StudentEnrolledEvent.class));
                    var state = context.component(StateManager.class);
                    var studentId = studentEnrolled.studentId();
                    var loadedEntity = state.loadManagedEntity(StudentCoursesReadModel.class, studentId, context)
                                            .join();
                    loadedEntity.applyStateChange(e -> Optional.ofNullable(e)
                                                               .orElse(new StudentCoursesReadModel(studentId))
                                                               .evolve(studentEnrolled)
                    );
                    return MessageStream.empty();
                }
        );
        return studentCoursesProjector;
    }

    private void verifyReadModelState(String studentId, Consumer<StudentCoursesReadModel> stateVerifier) {
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   UnitOfWork uow = unitOfWorkFactory.create();
                   var result = uow.executeWithResult(
                           context -> context.component(StateManager.class)
                                             .repository(StudentCoursesReadModel.class, String.class)
                                             .load(studentId, context)
                   ).join();
                   stateVerifier.accept(result.entity());
               });
    }

    private static EntityEvolver<StudentCoursesReadModel> readModelEvolver() {
        return (entity, event, context) -> {
            if (event.type().qualifiedName().equals(new QualifiedName(StudentEnrolledEvent.class))) {
                var payload = event.payloadAs(StudentEnrolledEvent.class);
                return entity.evolve(payload);
            }
            return entity;
        };
    }
}
