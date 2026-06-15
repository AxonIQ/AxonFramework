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

package org.axonframework.examples.demo.coursecatalog.catalog.events;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * A student was enrolled in a course.
 *
 * @param courseId  the course
 * @param studentId the enrolled student
 * @param region    the region the enrolment was processed in, carried over from the student's
 *                  registration as resolved for this read (see {@link RequestRegion})
 */
@Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "StudentEnrolledInCourse", version = "1.0.0")
public record StudentEnrolledInCourse(
        @EventTag(key = CourseCatalogTags.COURSE_ID) CourseId courseId,
        @EventTag(key = CourseCatalogTags.STUDENT_ID) StudentId studentId,
        String region
) {

    /**
     * Convenience constructor for an enrolment with no explicit region, defaulting to
     * {@link RequestRegion#UNKNOWN_REGION}.
     *
     * @param courseId  the course
     * @param studentId the enrolled student
     */
    public StudentEnrolledInCourse(CourseId courseId, StudentId studentId) {
        this(courseId, studentId, RequestRegion.UNKNOWN_REGION);
    }
}
