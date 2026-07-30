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

package org.axonframework.examples.demo.multitenancy.university.write.enrollstudent;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.examples.demo.multitenancy.university.events.CourseOpened;
import org.axonframework.examples.demo.multitenancy.university.events.StudentEnrolledInCourse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a snapshot of the course survives the round trip through the default converter.
 * <p>
 * A snapshot is the entity itself handed to the {@code Converter}. This is the assertion that decides
 * whether the entity may carry a snapshot policy at all: a shape the converter cannot round-trip is stored
 * as an empty document without failing, and the course then comes back blank, turning a full course into
 * one that was never opened. The in-memory snapshot store keeps the entity instance as-is, so it never
 * exercises this path, which is why this is asserted against the converter directly.
 */
class CourseSnapshotConversionTest {

    private static final String COURSE_ID = "cs-101";

    private final JacksonConverter converter = new JacksonConverter();

    @Test
    void theCourseSurvivesTheRoundTripThroughTheDefaultConverter() {
        EnrollStudentCommandHandler.State course = filledCourse();

        Object document = converter.convert(course, String.class);
        EnrollStudentCommandHandler.State restored =
                converter.convert(document, EnrollStudentCommandHandler.State.class);

        assertThat(restored).isEqualTo(course);
    }

    // Reading a stored snapshot back outside the framework goes through CourseSnapshot, so the document the
    // entity produces has to populate that view too.
    @Test
    void theStoredDocumentReadsBackIntoTheCourseSnapshotView() {
        EnrollStudentCommandHandler.State course = filledCourse();

        Object document = converter.convert(course, String.class);
        CourseSnapshot view = converter.convert(document, CourseSnapshot.class);

        assertThat(view.open()).isTrue();
        assertThat(view.capacity()).isEqualTo(2);
        assertThat(view.enrolledStudents()).containsExactlyInAnyOrder("alice", "bob");
    }

    // A document without the students, as an entity that cannot be converted would produce, must not come
    // back looking like a valid course.
    @Test
    void anEmptyDocumentDoesNotReadBackAsAnOpenCourse() {
        CourseSnapshot view = converter.convert("{}", CourseSnapshot.class);

        assertThat(view.open()).isFalse();
        assertThat(view.enrolledStudents()).isEmpty();
    }

    private static EnrollStudentCommandHandler.State filledCourse() {
        return new EnrollStudentCommandHandler.State()
                .evolve(new CourseOpened(COURSE_ID, 2))
                .evolve(new StudentEnrolledInCourse(COURSE_ID, "alice"))
                .evolve(new StudentEnrolledInCourse(COURSE_ID, "bob"));
    }
}
