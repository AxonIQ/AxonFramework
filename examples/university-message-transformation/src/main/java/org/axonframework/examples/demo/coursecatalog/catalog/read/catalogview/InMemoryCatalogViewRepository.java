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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory snapshot of the catalog view. Idempotent state (sets keyed by id, text
 * deduplication for announcements) so an event replay produces the same snapshot
 * as the original delivery.
 */
class InMemoryCatalogViewRepository implements CatalogViewRepository {

    private final Map<CourseId, CatalogViewReadModel> courses = new LinkedHashMap<>();
    private final Map<CourseId, Set<StudentId>> enrolments = new HashMap<>();
    private final Set<String> announcements = new LinkedHashSet<>();
    private final Set<StudentId> registeredStudents = new HashSet<>();

    @Override
    public synchronized void saveCourse(CatalogViewReadModel course) {
        int currentEnrolments = enrolments.getOrDefault(course.courseId(), Set.of()).size();
        courses.put(course.courseId(), course.withEnrolments(currentEnrolments));
    }

    @Override
    public synchronized Optional<CatalogViewReadModel> findCourse(CourseId courseId) {
        return Optional.ofNullable(courses.get(courseId));
    }

    @Override
    public synchronized void addAnnouncement(String announcement) {
        announcements.add(announcement);
    }

    @Override
    public synchronized void registerStudent(StudentId studentId) {
        registeredStudents.add(studentId);
    }

    @Override
    public synchronized void recordEnrolment(CourseId courseId, StudentId studentId) {
        Set<StudentId> enrolled = enrolments.computeIfAbsent(courseId, k -> new HashSet<>());
        enrolled.add(studentId);
        CatalogViewReadModel row = courses.get(courseId);
        if (row != null) {
            courses.put(courseId, row.withEnrolments(enrolled.size()));
        }
    }

    @Override
    public synchronized CourseCatalogView snapshot() {
        return new CourseCatalogView(
                List.copyOf(courses.values()),
                List.copyOf(announcements),
                registeredStudents.size()
        );
    }
}
