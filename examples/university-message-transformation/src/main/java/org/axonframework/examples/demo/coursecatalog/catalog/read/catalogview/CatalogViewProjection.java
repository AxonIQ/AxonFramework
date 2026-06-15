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

import org.axonframework.examples.demo.coursecatalog.catalog.events.CourseCapacityChanged;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.RegistrationClosed;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentRegistered;
import org.axonframework.examples.demo.coursecatalog.catalog.events.SystemAnnouncement;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

/**
 * Tracking projection building the {@link CourseCatalogView}. Receives every
 * catalog event in the current shape: historic shapes on the disk are lifted by the
 * transformation chain before the projection sees them.
 */
class CatalogViewProjection {

    private final CatalogViewRepository repository;

    CatalogViewProjection(CatalogViewRepository repository) {
        this.repository = repository;
    }

    @EventHandler
    void on(CoursePublished event) {
        repository.saveCourse(new CatalogViewReadModel(
                event.courseId(),
                event.name(),
                event.range(),
                0,
                false
        ));
    }

    @EventHandler
    void on(CourseCapacityChanged event) {
        repository.findCourse(event.courseId())
                  .map(row -> row.withRange(event.newRange()))
                  .ifPresent(repository::saveCourse);
    }

    @EventHandler
    void on(StudentEnrolledInCourse event) {
        repository.recordEnrolment(event.courseId(), event.studentId(), event.region());
    }

    @EventHandler
    void on(RegistrationClosed event) {
        repository.findCourse(event.courseId())
                  .map(CatalogViewReadModel::closed)
                  .ifPresent(repository::saveCourse);
    }

    @EventHandler
    void on(StudentRegistered event) {
        repository.registerStudent(event.studentId());
    }

    @EventHandler
    void on(SystemAnnouncement event) {
        repository.addAnnouncement(event.text());
    }
}
