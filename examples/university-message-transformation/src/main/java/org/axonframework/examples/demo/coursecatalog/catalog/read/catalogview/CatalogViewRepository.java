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

import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;

import java.util.Optional;

/**
 * Hexagonal port for the catalog view's read model. Implementations hold a single
 * mutable snapshot of the whole catalog; the projection writes into it and the
 * query handler reads from it.
 */
public interface CatalogViewRepository {

    /** @param course the row to insert or replace */
    void saveCourse(CatalogViewReadModel course);

    /**
     * @param courseId the course to find
     * @return the row if present
     */
    Optional<CatalogViewReadModel> findCourse(CourseId courseId);

    /** @param announcement the announcement body to append */
    void addAnnouncement(String announcement);

    /** Increments the registered-students counter by one. */
    void incrementRegisteredStudents();

    /** @return the full snapshot of the catalog */
    CourseCatalogView snapshot();
}
