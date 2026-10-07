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

package org.axonframework.examples.springcloud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, intentionally non-event-sourced store of {@link Course} views.
 * <p>
 * Kept as a plain map, shared between {@link CourseCommandHandlers} and {@link CourseQueryHandlers}, so this example
 * stays focused on Spring Cloud message distribution rather than event transport or storage. See the module README
 * for the follow-up on durable storage.
 */
@Component
@Profile("courses")
class CourseCatalog {

    private final Map<String, Course> courses = new ConcurrentHashMap<>();
    private final String nodeName;

    CourseCatalog(@Value("${university.node-name}") String nodeName) {
        this.nodeName = nodeName;
    }

    Course create(String courseId, String name) {
        return courses.compute(courseId, (id, ignored) -> new Course(id, name, nodeName));
    }

    Course rename(String courseId, String name) {
        return courses.computeIfPresent(courseId, (id, course) -> new Course(id, name, course.handledBy()));
    }

    Course find(String courseId) {
        return courses.get(courseId);
    }
}
