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

package org.axonframework.examples.demo.multitenancy.university.read.statistics;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.events.CourseOpened;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the projection that builds a tenant's course statistics from its enrollment events.
 * <p>
 * The projection is handed the components of the tenant whose event store the event was streamed from, so
 * these tests hand it a single tenant's stores directly. Which tenant's stores it receives is the framework's
 * job, and the Spring Boot integration test covers that against a real Axon Server.
 * <p>
 * Handling the same event twice is exercised on purpose. Events are delivered at least once, and re-opening
 * the stream on a tenant change makes a repeat more likely than usual, so the demo's exact-count assertions
 * only hold because the read model is keyed on identity rather than counted, and because what is emitted to a
 * subscriber follows that write rather than the event.
 */
class CourseStatisticsProjectionTest {

    private static final String COURSE_ID = "cs-101";

    private final CourseStatisticsProjection testSubject = new CourseStatisticsProjection();
    private final CourseStatisticsStore statisticsStore = CourseStatisticsStore.inMemory("springfield");
    private final AuditLog auditLog = AuditLog.inMemory("springfield");
    private final RecordingQueryUpdateEmitter updateEmitter = new RecordingQueryUpdateEmitter();

    @Test
    void projectsAnEnrollmentIntoTheStatisticsAndTheAuditLog() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);

        assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 1));
        assertThat(auditLog.entries()).containsExactly("Enrolled student [alice] in course [" + COURSE_ID + "]");
    }

    @Nested
    class SubscriptionUpdates {

        @Test
        void emitsTheFreshStatisticsForEveryEnrollment() {
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

            assertThat(updateEmitter.emitted()).containsExactly(
                    new TenantStatistics(List.of(new CourseStatistics(COURSE_ID, 1)), 1),
                    new TenantStatistics(List.of(new CourseStatistics(COURSE_ID, 2)), 2));
        }

        @Test
        void completesTheSubscriptionOnceTheCourseHasNoSeatsLeft() {
            testSubject.on(new CourseOpened(COURSE_ID, 2), statisticsStore);

            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

            // Both enrollments emit, and only the one that filled the course completes.
            assertThat(updateEmitter.emitted()).hasSize(2);
            assertThat(updateEmitter.completions()).isEqualTo(1);
        }

        @Test
        void keepsTheSubscriptionOpenWhileTheCourseHasSeatsLeft() {
            testSubject.on(new CourseOpened(COURSE_ID, 3), statisticsStore);

            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

            assertThat(updateEmitter.emitted()).hasSize(2);
            assertThat(updateEmitter.completions()).isZero();
        }

        @Test
        void emitsNothingForAnEnrollmentItAlreadyHeld() {
            testSubject.on(new CourseOpened(COURSE_ID, 3), statisticsStore);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);

            // The same event again. The read model is unchanged, so there is nothing fresh to report.
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);

            assertThat(updateEmitter.emitted()).hasSize(1);
            assertThat(updateEmitter.completions()).isZero();
        }

        @Test
        void doesNotCompleteAgainWhenTheEnrollmentThatFilledTheCourseIsRedelivered() {
            testSubject.on(new CourseOpened(COURSE_ID, 2), statisticsStore);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

            assertThat(updateEmitter.emitted()).hasSize(2);
            assertThat(updateEmitter.completions()).isEqualTo(1);
        }

        @Test
        void keepsTheSubscriptionOpenWhenTheCourseCapacityWasNeverProjected() {
            // Without a projected capacity there is nothing to call full, so nothing is completed either.
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);

            assertThat(updateEmitter.completions()).isZero();
        }
    }

    @Test
    void countsEachStudentOfACourseSeparately() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

        assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 2));
        assertThat(auditLog.entries()).hasSize(2);
    }

    @Nested
    class RepeatedDelivery {

        @Test
        void handlingTheSameEnrollmentTwiceLeavesTheStatisticsUnchanged() {
            StudentEnrolledInCourse event = new StudentEnrolledInCourse(COURSE_ID, "alice");

            testSubject.on(event, statisticsStore, auditLog, updateEmitter);
            testSubject.on(event, statisticsStore, auditLog, updateEmitter);

            assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 1));
        }

        @Test
        void handlingTheSameEnrollmentTwiceLeavesTheAuditLogUnchanged() {
            StudentEnrolledInCourse event = new StudentEnrolledInCourse(COURSE_ID, "alice");

            testSubject.on(event, statisticsStore, auditLog, updateEmitter);
            testSubject.on(event, statisticsStore, auditLog, updateEmitter);

            assertThat(auditLog.entries()).hasSize(1);
        }

        @Test
        void aRepeatOfOneEnrollmentDoesNotAffectAnother() {
            StudentEnrolledInCourse alice = new StudentEnrolledInCourse(COURSE_ID, "alice");

            testSubject.on(alice, statisticsStore, auditLog, updateEmitter);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);
            testSubject.on(alice, statisticsStore, auditLog, updateEmitter);

            assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 2));
            assertThat(auditLog.entries()).hasSize(2);
        }
    }

    @Test
    void keepsTheEnrollmentsOfTwoCoursesApart() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
        testSubject.on(new StudentEnrolledInCourse("law-200", "bob"), statisticsStore, auditLog, updateEmitter);

        assertThat(statisticsStore.statistics()).containsExactlyInAnyOrder(
                new CourseStatistics(COURSE_ID, 1),
                new CourseStatistics("law-200", 1));
    }

    @Test
    void aCourseWithoutEnrollmentsIsNotReported() {
        assertThat(statisticsStore.statistics()).isEmpty();
        assertThat(auditLog.entries()).isEmpty();
    }

    @Test
    void theAuditLogKeepsTheOrderThingsHappenedIn() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog, updateEmitter);
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog, updateEmitter);

        assertThat(auditLog.entries()).containsExactly(
                "Enrolled student [alice] in course [" + COURSE_ID + "]",
                "Enrolled student [bob] in course [" + COURSE_ID + "]");
    }

    // A recording QueryUpdateEmitter rather than a mock: the interaction under test is "what was emitted",
    // a plain state-based capture, not a call that must or must not have happened.
    private static final class RecordingQueryUpdateEmitter implements QueryUpdateEmitter {

        // The suppliers, resolved only when the test asks what was emitted, so an update built from live state
        // would show up as the final state rather than the state its own enrollment left behind.
        private final List<Supplier<Object>> emitted = new ArrayList<>();
        private int completions;

        List<Object> emitted() {
            return emitted.stream().map(Supplier::get).toList();
        }

        int completions() {
            return completions;
        }

        @Override
        public <Q> void emit(Class<Q> queryType, Predicate<? super Q> filter, Supplier<Object> updateSupplier) {
            emitted.add(updateSupplier);
        }

        @Override
        public void emit(QualifiedName queryName, Predicate<Object> filter, Supplier<Object> updateSupplier) {
            emitted.add(updateSupplier);
        }

        @Override
        public <Q> void complete(Class<Q> queryType, Predicate<? super Q> filter) {
            completions++;
        }

        @Override
        public void complete(QualifiedName queryName, Predicate<Object> filter) {
            completions++;
        }

        @Override
        public <Q> void completeExceptionally(Class<Q> queryType, Predicate<? super Q> filter, Throwable cause) {
            // Not exercised by these tests.
        }

        @Override
        public void completeExceptionally(QualifiedName queryName, Predicate<Object> filter, Throwable cause) {
            // Not exercised by these tests.
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("emitted", emitted);
        }
    }
}
