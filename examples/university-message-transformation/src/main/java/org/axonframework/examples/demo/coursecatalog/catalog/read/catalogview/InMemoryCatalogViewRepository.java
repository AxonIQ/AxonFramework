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

import java.util.ArrayList;
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
    private final Map<CourseId, Map<StudentId, String>> enrolments = new LinkedHashMap<>();
    private final Set<String> announcements = new LinkedHashSet<>();
    private final Set<StudentId> registeredStudents = new HashSet<>();
    private final Map<StudentId, String> welcomeMessages = new LinkedHashMap<>();
    private final Set<Integer> heartbeatSequences = new HashSet<>();

    @Override
    public synchronized void saveCourse(CatalogViewReadModel course) {
        int currentEnrolments = enrolments.getOrDefault(course.courseId(), Map.of()).size();
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
    public synchronized void recordEnrolment(CourseId courseId, StudentId studentId, String region) {
        Map<StudentId, String> enrolled = enrolments.computeIfAbsent(courseId, k -> new LinkedHashMap<>());
        enrolled.put(studentId, region);
        CatalogViewReadModel row = courses.get(courseId);
        if (row != null) {
            courses.put(courseId, row.withEnrolments(enrolled.size()));
        }
    }

    @Override
    public synchronized void recordWelcomeMessage(StudentId studentId, String body) {
        welcomeMessages.put(studentId, body);
    }

    @Override
    public synchronized void recordHeartbeat(int sequence) {
        heartbeatSequences.add(sequence);
    }

    @Override
    public synchronized CourseCatalogView snapshot() {
        List<EnrolmentReadModel> enrolmentRows = new ArrayList<>();
        enrolments.forEach((courseId, byStudent) -> byStudent.forEach(
                (studentId, region) -> enrolmentRows.add(new EnrolmentReadModel(courseId, studentId, region))));
        return new CourseCatalogView(
                List.copyOf(courses.values()),
                List.copyOf(enrolmentRows),
                List.copyOf(announcements),
                registeredStudents.size(),
                welcomeMessages.entrySet().stream()
                               .map(entry -> new WelcomeMessageView(entry.getKey(), entry.getValue()))
                               .toList(),
                heartbeatSequences.size()
        );
    }
}
