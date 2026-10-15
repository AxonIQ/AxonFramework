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

import org.axonframework.eventsourcing.commandhandling.AppendCriteriaInterceptor;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentNameChangedEvent;
import org.axonframework.integrationtests.testsuite.student.state.Course;
import org.axonframework.integrationtests.testsuite.student.state.Student;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests proving what append criteria change for a running application: which concurrent events a command
 * still conflicts with, and which consistency checks a command that loads nothing gets.
 * <p>
 * Every scenario that shows a benefit is paired with the same scenario without append criteria, so the difference is
 * the feature. Two commands interleave the way concurrent ones on different nodes do: the first loads its entities and
 * is held, a second command commits, and only then does the first append. Latches make that order deterministic.
 */
public abstract class AppendCriteriaIT extends AbstractCommandHandlingStudentIT {

    private static final QualifiedName STUDENT_ENROLLED = new QualifiedName(StudentEnrolledEvent.class);
    private static final QualifiedName ENROLL_STUDENT = new QualifiedName(EnrollStudent.class);

    private final String studentId = createId("student");
    private final String courseId = createId("course");
    private final Interleaving interleaving = new Interleaving();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Nested
    class NarrowedBoundary {

        @Test
        void withoutAppendCriteriaAConcurrentEventOfAnyTypeOnALoadedEntityRejectsTheCommand() {
            // given the default boundary: everything the enrollment sourced
            registerCommandHandlers(handlerPhase -> handlerPhase
                    .autodetectedCommandHandlingComponent(c -> new SymmetricEnrollmentHandler(interleaving))
                    .autodetectedCommandHandlingComponent(c -> new ConcurrentWriterHandler()));
            startApp();

            // when a rename, irrelevant to the enrollment decision, commits while the enrollment is in progress
            CompletableFuture<Object> enrollment = sendWhileAnotherCommandCommits(
                    new EnrollStudent(studentId, courseId), new RenameStudent(studentId, "Jane")
            );

            // then the enrollment conflicts anyway
            assertRejectedAsConflict(() -> enrollment.orTimeout(5, TimeUnit.SECONDS).join());
            assertThat(enrollmentsOf(studentId)).isZero();
        }

        @Test
        void narrowedCriteriaAcceptAConcurrentEventOfATypeTheDecisionDoesNotDependOn() {
            // given the boundary narrowed to enrollments
            registerCommandHandlers(handlerPhase -> handlerPhase
                    .autodetectedCommandHandlingComponent(c -> new NarrowedEnrollmentHandler(interleaving))
                    .autodetectedCommandHandlingComponent(c -> new ConcurrentWriterHandler()));
            startApp();

            // when the same irrelevant rename commits while the enrollment is in progress
            CompletableFuture<Object> enrollment = sendWhileAnotherCommandCommits(
                    new EnrollStudent(studentId, courseId), new RenameStudent(studentId, "Jane")
            );

            // then the enrollment is stored instead of being rejected and retried
            enrollment.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(enrollmentsOf(studentId)).isOne();
        }

        @Test
        void narrowedCriteriaStillRejectAConcurrentEventTheDecisionDependsOn() {
            // given the boundary narrowed to enrollments
            registerCommandHandlers(handlerPhase -> handlerPhase
                    .autodetectedCommandHandlingComponent(c -> new NarrowedEnrollmentHandler(interleaving))
                    .autodetectedCommandHandlingComponent(c -> new ConcurrentWriterHandler()));
            startApp();

            // when an enrollment of the same student commits while this enrollment is in progress
            CompletableFuture<Object> enrollment = sendWhileAnotherCommandCommits(
                    new EnrollStudent(studentId, courseId), new RecordEnrollment(studentId, createId("other-course"))
            );

            // then this enrollment conflicts, so the invariant stays protected
            assertRejectedAsConflict(() -> enrollment.orTimeout(5, TimeUnit.SECONDS).join());
            assertThat(enrollmentsOf(studentId)).isOne();
        }

        @Test
        void declarativeInterceptorNarrowsTheBoundaryLikeTheAnnotation() {
            // given the boundary narrowed through an AppendCriteriaInterceptor instead of an annotation; it intercepts
            // every command of the module, so it narrows the enrollment and leaves other commands as they are
            registerCommandHandlers(handlerPhase -> handlerPhase
                    .autodetectedCommandHandlingComponent(c -> new SymmetricEnrollmentHandler(interleaving))
                    .autodetectedCommandHandlingComponent(c -> new ConcurrentWriterHandler())
                    .intercepted(c -> new AppendCriteriaInterceptor(
                            c.getComponent(EventStore.class),
                            (command, context, sourcingCriteria) ->
                                    ENROLL_STUDENT.equals(command.type().qualifiedName())
                                            ? sourcingCriteria.withEventTypes(STUDENT_ENROLLED)
                                            : sourcingCriteria
                    )));
            startApp();

            // when an irrelevant rename commits while the enrollment is in progress
            CompletableFuture<Object> enrollment = sendWhileAnotherCommandCommits(
                    new EnrollStudent(studentId, courseId), new RenameStudent(studentId, "Jane")
            );

            // then
            enrollment.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(enrollmentsOf(studentId)).isOne();
        }

        /**
         * Sends {@code command} from another thread, lets {@code concurrentCommand} commit once {@code command} loaded
         * its entities, and only then lets {@code command} append, the way two commands interleave on different nodes.
         */
        private CompletableFuture<Object> sendWhileAnotherCommandCommits(Object command, Object concurrentCommand) {
            CompletableFuture<Object> result =
                    CompletableFuture.supplyAsync(() -> commandGateway.sendAndWait(command), executor);
            interleaving.awaitLoaded();
            sendCommand(concurrentCommand);
            interleaving.release();
            return result;
        }
    }

    @Nested
    class CommandThatLoadsNothing {

        @Test
        void withoutAppendCriteriaASecondReservationIsAppendedAsWell() {
            // given a command that loads nothing, so it has no consistency boundary at all
            registerCommandHandlers(handlerPhase -> handlerPhase.autodetectedCommandHandlingComponent(
                    c -> new UncheckedReservationHandler()
            ));
            startApp();
            sendCommand(new ReserveEnrollment(studentId, courseId));

            // when the same reservation is made again
            sendCommand(new ReserveEnrollment(studentId, courseId));

            // then nothing stops the duplicate
            assertThat(enrollmentsOf(studentId)).isEqualTo(2);
        }

        @Test
        void criteriaReserveTheKeyAgainstTheWholeHistory() {
            // given the same command, declaring the reservation through its criteria
            registerCommandHandlers(handlerPhase -> handlerPhase.autodetectedCommandHandlingComponent(
                    c -> new CheckedReservationHandler()
            ));
            startApp();
            sendCommand(new ReserveEnrollment(studentId, courseId));

            // when the same reservation is made again
            // then the criteria find the first one without the command having loaded anything
            assertRejectedAsConflict(() -> sendCommand(new ReserveEnrollment(studentId, courseId)));
            assertThat(enrollmentsOf(studentId)).isOne();
        }

        @Test
        void criteriaLeaveOtherKeysAvailable() {
            // given
            String otherStudentId = createId("other-student");
            registerCommandHandlers(handlerPhase -> handlerPhase.autodetectedCommandHandlingComponent(
                    c -> new CheckedReservationHandler()
            ));
            startApp();
            sendCommand(new ReserveEnrollment(studentId, courseId));

            // when
            sendCommand(new ReserveEnrollment(otherStudentId, courseId));

            // then
            assertThat(enrollmentsOf(otherStudentId)).isOne();
        }
    }

    /**
     * Asserts that the given {@code command} is rejected because a conflicting event was found, rather than for any
     * other reason. Infrastructures reporting conflicts differently can override it.
     *
     * @param command the dispatch expected to fail on a conflict
     */
    protected void assertRejectedAsConflict(ThrowableAssert.ThrowingCallable command) {
        assertThatThrownBy(command)
                .hasStackTraceContaining(AppendEventsTransactionRejectedException.class.getSimpleName());
    }

    /**
     * Counts the enrollments stored for the given student.
     */
    protected long enrollmentsOf(String studentId) {
        EventCriteria enrollments = EventCriteria.havingTags(new Tag("Student", studentId))
                                                 .andBeingOneOfTypes(STUDENT_ENROLLED);
        // uses the MessageStream API rather than Reactor, which not every infrastructure running this suite provides
        SourcingCondition condition = SourcingCondition.conditionFor(enrollments);
        return unitOfWorkFactory.create()
                                .executeWithResult(context -> context.component(EventStore.class)
                                                                     .transaction(context)
                                                                     .source(condition)
                                                                     .reduce(0L, (count, entry) -> count + 1))
                                .orTimeout(5, TimeUnit.SECONDS)
                                .join();
    }

    record EnrollStudent(String studentId, String courseId) {

    }

    record ReserveEnrollment(String studentId, String courseId) {

    }

    record RenameStudent(String studentId, String name) {

    }

    record RecordEnrollment(String studentId, String courseId) {

    }

    /**
     * Holds a command after it loaded its entities until the test released it, failing the command rather than
     * waiting forever when it is never released.
     */
    static final class Interleaving {

        private final CountDownLatch loaded = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void afterLoading() {
            loaded.countDown();
            await(released, "released by the test");
        }

        void awaitLoaded() {
            await(loaded, "loaded by the command");
        }

        void release() {
            released.countDown();
        }

        private static void await(CountDownLatch latch, String what) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to be " + what);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting to be " + what, e);
            }
        }
    }

    /**
     * Stands in for another node, appending without loading anything, so its commands always commit.
     */
    static class ConcurrentWriterHandler {

        @CommandHandler
        void handle(RenameStudent command, EventAppender eventAppender) {
            eventAppender.append(new StudentNameChangedEvent(command.studentId(), command.name()));
        }

        @CommandHandler
        void handle(RecordEnrollment command, EventAppender eventAppender) {
            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));
        }
    }

    /**
     * Loads the student and the course, waits for the test to let a concurrent command commit, and then enrolls the
     * student. Without append criteria, its boundary is everything it loaded.
     */
    static class SymmetricEnrollmentHandler {

        private final Interleaving interleaving;

        SymmetricEnrollmentHandler(Interleaving interleaving) {
            this.interleaving = interleaving;
        }

        @CommandHandler
        void handle(EnrollStudent command,
                    @InjectEntity(idProperty = "studentId") Student student,
                    @InjectEntity(idProperty = "courseId") Course course,
                    EventAppender eventAppender) {
            interleaving.afterLoading();
            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));
        }
    }

    /**
     * The same handler, narrowing its boundary to the enrollments of the loaded student and course. It declares its own
     * handler, as a builder only applies to the handlers of the class declaring them.
     */
    static class NarrowedEnrollmentHandler {

        private final Interleaving interleaving;

        NarrowedEnrollmentHandler(Interleaving interleaving) {
            this.interleaving = interleaving;
        }

        @CommandHandler
        void handle(EnrollStudent command,
                    @InjectEntity(idProperty = "studentId") Student student,
                    @InjectEntity(idProperty = "courseId") Course course,
                    EventAppender eventAppender) {
            interleaving.afterLoading();
            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));
        }

        @AppendCriteriaBuilder
        static EventCriteria appendCriteria(EnrollStudent command, EventCriteria sourcingCriteria) {
            return sourcingCriteria.withEventTypes(STUDENT_ENROLLED);
        }
    }

    /**
     * Appends a reservation without loading anything, so it has no consistency boundary.
     */
    static class UncheckedReservationHandler {

        @CommandHandler
        void handle(ReserveEnrollment command, EventAppender eventAppender) {
            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));
        }
    }

    /**
     * The same handler, declaring the reservation as its append criteria.
     */
    static class CheckedReservationHandler {

        @CommandHandler
        void handle(ReserveEnrollment command, EventAppender eventAppender) {
            eventAppender.append(new StudentEnrolledEvent(command.studentId(), command.courseId()));
        }

        @AppendCriteriaBuilder
        static EventCriteria appendCriteria(ReserveEnrollment command, EventCriteria sourcingCriteria) {
            return EventCriteria.havingTags(new Tag("Student", command.studentId()))
                                .andBeingOneOfTypes(STUDENT_ENROLLED);
        }
    }
}
