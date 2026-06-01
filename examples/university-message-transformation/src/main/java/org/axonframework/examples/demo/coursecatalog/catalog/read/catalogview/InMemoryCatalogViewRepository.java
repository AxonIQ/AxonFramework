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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single in-memory snapshot of the catalog view. Synchronizes on the courses map to
 * keep mutation atomic; reads return defensive copies so callers cannot observe
 *  the mid-mutation state.
 */
class InMemoryCatalogViewRepository implements CatalogViewRepository {

    private final Map<CourseId, CatalogViewReadModel> courses = new LinkedHashMap<>();
    private final List<String> announcements = new CopyOnWriteArrayList<>();
    private final AtomicInteger registeredStudents = new AtomicInteger();

    @Override
    public synchronized void saveCourse(CatalogViewReadModel course) {
        courses.put(course.courseId(), course);
    }

    @Override
    public synchronized Optional<CatalogViewReadModel> findCourse(CourseId courseId) {
        return Optional.ofNullable(courses.get(courseId));
    }

    @Override
    public void addAnnouncement(String announcement) {
        announcements.add(announcement);
    }

    @Override
    public void incrementRegisteredStudents() {
        registeredStudents.incrementAndGet();
    }

    @Override
    public synchronized CourseCatalogView snapshot() {
        return new CourseCatalogView(
                List.copyOf(courses.values()),
                List.copyOf(announcements),
                registeredStudents.get()
        );
    }
}
