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

import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;

/**
 * One course row in the catalog read model.
 *
 * @param courseId           the course identifier
 * @param name               human-readable course name
 * @param range              current capacity range
 * @param enrolments         number of students currently enrolled
 * @param registrationClosed whether registration for this course is closed
 */
public record CatalogViewReadModel(
        CourseId courseId,
        String name,
        CapacityRange range,
        int enrolments,
        boolean registrationClosed
) {

    /**
     * @param newRange the capacity range to set on the copy
     * @return a copy with the given capacity range
     */
    public CatalogViewReadModel withRange(CapacityRange newRange) {
        return new CatalogViewReadModel(courseId, name, newRange, enrolments, registrationClosed);
    }

    /**
     * @param newEnrolments the absolute enrolment count for the copy
     * @return a copy with the given enrolment count
     */
    public CatalogViewReadModel withEnrolments(int newEnrolments) {
        return new CatalogViewReadModel(courseId, name, range, newEnrolments, registrationClosed);
    }

    /** @return a copy marked as closed for registration */
    public CatalogViewReadModel closed() {
        return new CatalogViewReadModel(courseId, name, range, enrolments, true);
    }
}
