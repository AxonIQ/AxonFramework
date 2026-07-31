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

import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * only hold because the read model is keyed on identity rather than counted.
 */
class CourseStatisticsProjectionTest {

    private static final String COURSE_ID = "cs-101";

    private final CourseStatisticsProjection testSubject = new CourseStatisticsProjection();
    private final CourseStatisticsStore statisticsStore = CourseStatisticsStore.inMemory("springfield");
    private final AuditLog auditLog = AuditLog.inMemory("springfield");

    @Test
    void projectsAnEnrollmentIntoTheStatisticsAndTheAuditLog() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog);

        assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 1));
        assertThat(auditLog.entries()).containsExactly("Enrolled student [alice] in course [" + COURSE_ID + "]");
    }

    @Test
    void countsEachStudentOfACourseSeparately() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog);
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog);

        assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 2));
        assertThat(auditLog.entries()).hasSize(2);
    }

    @Nested
    class RepeatedDelivery {

        @Test
        void handlingTheSameEnrollmentTwiceLeavesTheStatisticsUnchanged() {
            StudentEnrolledInCourse event = new StudentEnrolledInCourse(COURSE_ID, "alice");

            testSubject.on(event, statisticsStore, auditLog);
            testSubject.on(event, statisticsStore, auditLog);

            assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 1));
        }

        @Test
        void handlingTheSameEnrollmentTwiceLeavesTheAuditLogUnchanged() {
            StudentEnrolledInCourse event = new StudentEnrolledInCourse(COURSE_ID, "alice");

            testSubject.on(event, statisticsStore, auditLog);
            testSubject.on(event, statisticsStore, auditLog);

            assertThat(auditLog.entries()).hasSize(1);
        }

        @Test
        void aRepeatOfOneEnrollmentDoesNotAffectAnother() {
            StudentEnrolledInCourse alice = new StudentEnrolledInCourse(COURSE_ID, "alice");

            testSubject.on(alice, statisticsStore, auditLog);
            testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog);
            testSubject.on(alice, statisticsStore, auditLog);

            assertThat(statisticsStore.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 2));
            assertThat(auditLog.entries()).hasSize(2);
        }
    }

    @Test
    void keepsTheEnrollmentsOfTwoCoursesApart() {
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog);
        testSubject.on(new StudentEnrolledInCourse("law-200", "bob"), statisticsStore, auditLog);

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
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "alice"), statisticsStore, auditLog);
        testSubject.on(new StudentEnrolledInCourse(COURSE_ID, "bob"), statisticsStore, auditLog);

        assertThat(auditLog.entries()).containsExactly(
                "Enrolled student [alice] in course [" + COURSE_ID + "]",
                "Enrolled student [bob] in course [" + COURSE_ID + "]");
    }
}
