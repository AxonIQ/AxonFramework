/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */

package io.axoniq.framework.examples.springcloud;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("courses")
public class CourseCatalog {

    private final Map<String, Course> courses = new ConcurrentHashMap<>();
    private final String nodeName;

    CourseCatalog(@Value("${university.node-name}") String nodeName) {
        this.nodeName = nodeName;
    }

    @CommandHandler
    public Course create(CreateCourse command) {
        return courses.compute(command.courseId(),
                               (courseId, ignored) -> new Course(courseId, command.name(), nodeName));
    }

    @QueryHandler
    public Course find(FindCourse query) {
        Course course = courses.get(query.courseId());
        if (course == null) {
            throw new IllegalArgumentException("Unknown course: " + query.courseId());
        }
        return course;
    }
}
