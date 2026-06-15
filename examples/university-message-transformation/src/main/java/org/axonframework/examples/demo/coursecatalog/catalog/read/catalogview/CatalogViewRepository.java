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
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;

import java.util.Optional;

/**
 * Hexagonal port for the catalog view's read model. Mutations are idempotent so the
 * projection survives an event replay.
 */
public interface CatalogViewRepository {

    /** @param course the row to insert or replace */
    void saveCourse(CatalogViewReadModel course);

    /**
     * @param courseId the course to find
     * @return the row if present
     */
    Optional<CatalogViewReadModel> findCourse(CourseId courseId);

    /** @param announcement the announcement body to record (duplicate text is a no-op) */
    void addAnnouncement(String announcement);

    /** @param studentId the registered student (re-registering the same id is a no-op) */
    void registerStudent(StudentId studentId);

    /**
     * @param courseId  the course the student enrolled in
     * @param studentId the enrolled student (re-enrolling the same id is a no-op)
     * @param region    the region resolved for this enrolment
     */
    void recordEnrolment(CourseId courseId, StudentId studentId, String region);

    /**
     * @param studentId the recipient of the welcome message
     * @param body      the message body (re-recording the same student replays to the same body)
     */
    void recordWelcomeMessage(StudentId studentId, String body);

    /** @param sequence the heartbeat's sequence number (re-recording the same sequence is a no-op) */
    void recordHeartbeat(int sequence);

    /** @return the full snapshot of the catalog */
    CourseCatalogView snapshot();
}
