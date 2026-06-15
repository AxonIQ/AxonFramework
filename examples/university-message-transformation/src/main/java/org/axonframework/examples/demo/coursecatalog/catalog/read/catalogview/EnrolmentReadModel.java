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

package org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview;

import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;

/**
 * One student-in-course enrolment row in the catalog read model.
 *
 * @param courseId  the course the student enrolled in
 * @param studentId the enrolled student
 * @param region    the region the enrolment was processed in, backfilled from the active
 *                  {@code ProcessingContext} for students whose registration predates regions
 *                  (see {@link RequestRegion})
 */
public record EnrolmentReadModel(
        CourseId courseId,
        StudentId studentId,
        String region
) {
}
