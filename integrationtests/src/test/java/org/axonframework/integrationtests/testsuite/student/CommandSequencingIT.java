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
import org.axonframework.integrationtests.testsuite.student.commands.EnrollStudentToCourseCommand;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.state.Course;
import org.axonframework.integrationtests.testsuite.student.state.Student;
import org.axonframework.messaging.commandhandling.MetadataRoutingStrategy;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.axonframework.messaging.commandhandling.interception.CommandSequencingInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests validating command sequencing behavior to prevent optimistic locking failures and deadlocks. These
 * tests verify that the {@link CommandSequencingInterceptor} properly sequences commands targeting the same entity.
 * <p>
 * The tests cover one critical scenario:
 * <ul>
 *   <li>Concurrent commands targeting the same entity - multiple enrollments to the same course</li>
 * </ul>
 *
 * @author Jakob Hatzl
 */
public abstract class CommandSequencingIT extends AbstractCommandHandlingStudentIT {

    public static final String ROUTING_KEY_METADATA_KEY = "CommandSequencingIT#routingKey";

    /**
     * Tests that multiple concurrent commands targeting the same entity (Course) do not cause optimistic locking
     * failures. Without command sequencing, concurrent updates to the same Course would result in version conflicts and
     * failures.
     * <p>
     * This test simulates 10 students enrolling in the same course concurrently. All enrollments should succeed without
     * any optimistic locking exceptions.
     */
    @Test
    void concurrentEnrollmentsToSameCourseDoNotCauseOptimisticLockingFailures() throws InterruptedException {
        // Setup
        int numberOfStudents = 10;
        String courseId = createId("course-1");
        ExecutorService executor = Executors.newFixedThreadPool(numberOfStudents);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch completionGate = new CountDownLatch(numberOfStudents);
        AtomicInteger successfulEnrollments = new AtomicInteger(0);
        ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

        // Register command handler
        registerCommandHandlers(handlerPhase -> handlerPhase.commandHandler(
                new org.axonframework.messaging.core.QualifiedName(EnrollStudentToCourseCommand.class),
                c -> (command, context) -> {
                    EventAppender eventAppender = EventAppender.forContext(context);
                    EnrollStudentToCourseCommand payload =
                            command.payloadAs(EnrollStudentToCourseCommand.class);
                    // load state to execute the command in the entities contexts
                    StateManager stateManager = context.component(StateManager.class);
                    stateManager.loadEntity(Course.class, payload.courseId(), context).join();
                    stateManager.loadEntity(Student.class, payload.studentId(), context).join();
                    eventAppender.append(new StudentEnrolledEvent(payload.studentId(), payload.courseId()));
                    successfulEnrollments.incrementAndGet();
                    return MessageStream.just(SUCCESSFUL_COMMAND_RESULT).cast();
                }
        ));
        startApp();

        // Submit concurrent enrollment commands
        for (int i = 0; i < numberOfStudents; i++) {
            String studentId = createId("student-" + i);
            CompletableFuture.runAsync(() -> {
                try {
                    startGate.await();
                    commandGateway.send(new EnrollStudentToCourseCommand(studentId, courseId),
                                        Metadata.from(Map.of(ROUTING_KEY_METADATA_KEY, courseId)))
                                  .onSuccess(m -> completionGate.countDown())
                                  .onError(e -> {
                                      exceptions.add(e);
                                      completionGate.countDown();
                                  });
                } catch (Exception e) {
                    exceptions.add(e);
                    completionGate.countDown();
                }
            }, executor);
        }
        // Release all threads simultaneously
        startGate.countDown();

        // Wait for all enrollments to complete
        assertTrue(completionGate.await(10, TimeUnit.SECONDS),
                   "All enrollments should complete within 10 seconds");

        // Verify no exceptions occurred
        if (!exceptions.isEmpty()) {
            Throwable first = exceptions.poll();
            AssertionError error = new AssertionError(
                    "Expected no exceptions during concurrent enrollments, but got: " + first.getMessage(),
                    first
            );
            exceptions.forEach(error::addSuppressed);
            throw error;
        }

        // Verify all enrollments succeeded
        assertEquals(numberOfStudents, successfulEnrollments.get(),
                     "All " + numberOfStudents + " enrollments should succeed");

        // Verify Course entity has exactly 10 enrolled students
        verifyCourseHasEnrolledStudents(courseId, numberOfStudents);

        executor.shutdown();
    }

    /**
     * Verifies that a Course entity has the expected number of enrolled students.
     */
    private void verifyCourseHasEnrolledStudents(String courseId, int expectedCount) {
        UnitOfWork uow = unitOfWorkFactory.create();
        Course course = uow.executeWithResult(context -> context.component(StateManager.class)
                                                                .repository(Course.class, String.class)
                                                                .load(courseId, context))
                           .join().entity();
        assertEquals(expectedCount,
                     course.getStudentsEnrolled().size(),
                     "Course should have " + expectedCount + " enrolled students");
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        return super.testSuiteConfigurer(configurer)
                    .messaging(messaging ->
                                       messaging.componentRegistry(cr -> cr.registerComponent(RoutingStrategy.class,
                                                                                              c -> new MetadataRoutingStrategy(
                                                                                                      ROUTING_KEY_METADATA_KEY)))
                    );
    }
}
