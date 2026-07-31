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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the tenant's course-statistics read model directly, for the two contracts the rest of the demo leans on:
 * an enrollment reports whether it was new, and a tenant is only out of seats once every course it holds is.
 */
class CourseStatisticsStoreTest {

    private static final String COURSE_ID = "cs-101";
    private static final String OTHER_COURSE_ID = "law-200";

    private final CourseStatisticsStore testSubject = CourseStatisticsStore.inMemory("springfield");

    @Nested
    class RecordingAnEnrollment {

        @Test
        void reportsAnEnrollmentItHasNotSeenBefore() {
            assertThat(testSubject.recordEnrollment(COURSE_ID, "alice")).isTrue();
        }

        @Test
        void reportsNothingNewForAnEnrollmentItAlreadyHolds() {
            testSubject.recordEnrollment(COURSE_ID, "alice");

            // Nothing changed, so there is nothing fresh to report.
            assertThat(testSubject.recordEnrollment(COURSE_ID, "alice")).isFalse();
            assertThat(testSubject.statistics()).containsExactly(new CourseStatistics(COURSE_ID, 1));
        }

        @Test
        void reportsTheSameStudentEnrollingInAnotherCourseAsNew() {
            testSubject.recordEnrollment(COURSE_ID, "alice");

            assertThat(testSubject.recordEnrollment(OTHER_COURSE_ID, "alice")).isTrue();
        }
    }

    @Nested
    class RunningOutOfSeats {

        @Test
        void aTenantHoldingNoCoursesIsNotOutOfSeats() {
            assertThat(testSubject.isEveryCourseFull()).isFalse();
        }

        @Test
        void aCourseWithSeatsLeftKeepsTheTenantFromBeingFull() {
            testSubject.recordCourseCapacity(COURSE_ID, 2);
            testSubject.recordEnrollment(COURSE_ID, "alice");

            assertThat(testSubject.isEveryCourseFull()).isFalse();
        }

        @Test
        void aTenantIsFullOnceItsOnlyCourseIs() {
            testSubject.recordCourseCapacity(COURSE_ID, 2);
            testSubject.recordEnrollment(COURSE_ID, "alice");
            testSubject.recordEnrollment(COURSE_ID, "bob");

            assertThat(testSubject.isEveryCourseFull()).isTrue();
        }

        @Test
        void oneFullCourseDoesNotMakeATenantWithAnotherOpenCourseFull() {
            testSubject.recordCourseCapacity(COURSE_ID, 1);
            testSubject.recordCourseCapacity(OTHER_COURSE_ID, 2);
            testSubject.recordEnrollment(COURSE_ID, "alice");
            testSubject.recordEnrollment(OTHER_COURSE_ID, "bob");

            // A subscription reports the whole tenant, so another course with seats keeps it open.
            assertThat(testSubject.isEveryCourseFull()).isFalse();
        }

        @Test
        void anOpenCourseWithoutEnrollmentsKeepsTheTenantFromBeingFull() {
            testSubject.recordCourseCapacity(COURSE_ID, 1);
            testSubject.recordCourseCapacity(OTHER_COURSE_ID, 1);
            testSubject.recordEnrollment(COURSE_ID, "alice");

            // The second course was opened and nobody enrolled yet, so the tenant can still receive one.
            assertThat(testSubject.isEveryCourseFull()).isFalse();
        }

        @Test
        void aCourseOfUnknownCapacityKeepsTheTenantFromBeingFull() {
            // No capacity recorded, so nothing about this course can be called full.
            testSubject.recordEnrollment(COURSE_ID, "alice");

            assertThat(testSubject.isEveryCourseFull()).isFalse();
        }

        @Test
        void recordingACourseAgainOverwritesTheCapacityHeldForIt() {
            testSubject.recordCourseCapacity(COURSE_ID, 1);
            testSubject.recordEnrollment(COURSE_ID, "alice");
            assertThat(testSubject.isEveryCourseFull()).isTrue();

            testSubject.recordCourseCapacity(COURSE_ID, 2);

            assertThat(testSubject.isEveryCourseFull()).isFalse();
        }
    }
}
