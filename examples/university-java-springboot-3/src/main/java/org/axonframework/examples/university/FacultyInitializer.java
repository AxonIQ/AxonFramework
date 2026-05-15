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

package org.axonframework.examples.university;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.university.read.coursestats.api.CoursesQueryResult;
import org.axonframework.examples.university.read.coursestats.api.FindAllCourses;
import org.axonframework.examples.university.read.coursestats.api.GetCourseStatsById;
import org.axonframework.examples.university.read.coursestats.projection.CoursesStats;
import org.axonframework.examples.university.shared.CourseId;
import org.axonframework.examples.university.write.changecoursecapacity.ChangeCourseCapacity;
import org.axonframework.examples.university.write.createcourse.CreateCourse;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Random;

import static org.axonframework.common.ProcessUtils.executeUntilTrue;
import static org.axonframework.examples.university.read.coursestats.projection.CoursesStatsProjectionConfiguration.PROCESSOR_NAME;

/**
 * Initializer of the faculty.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FacultyInitializer implements ApplicationRunner {

    private static final List<CoursesStats> COURSES = List.of(
            new CoursesStats(CourseId.of("1"), "Event Sourcing in Practice", 3, 0),
            new CoursesStats(CourseId.of("2"), "Event Modeling", 5, 0),
            new CoursesStats(CourseId.of("3"), "Killing the Aggregate", 10, 0)
    );


    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final AxonConfiguration axonConfiguration;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        waitUntilReady();
        initialize();
    }

    private void waitUntilReady() {
        var processor = axonConfiguration.getComponents(StreamingEventProcessor.class).get(PROCESSOR_NAME);
        log.info("[FACULTY] Waiting for course statistics projection replay to finish...");
        executeUntilTrue(
                () -> !processor.isReplaying(),
                1_000,
                30
        );
        log.info("[FACULTY] Done. Course statistics is up to date.");
    }

    private void initialize() throws Exception {
        log.info("[FACULTY] Initializing faculty...");

        var allCourses = queryGateway.queryMany(new FindAllCourses(), CoursesQueryResult.class).get();
        if (allCourses.isEmpty()) {
            log.info("[FACULTY] No courses found, creating new predefined courses...");
            COURSES.forEach(course -> {
                commandGateway.sendAndWait(new CreateCourse(
                        course.courseId(),
                        course.name(),
                        course.capacity()
                ));
                log.info("[FACULTY] Created course '{}' with id '{}' and capacity '{}'",
                         course.name(),
                         course.courseId(),
                         course.capacity());
            });
        } else {
            log.info("[FACULTY] {} courses found.", allCourses.size());
            allCourses.forEach(courseResult -> {
                var course = courseResult.courseStats();
                log.info("[FACULTY] Found course '{}' with id '{}' and capacity '{}'",
                         course.name(),
                         course.courseId(),
                         course.capacity());
            });
        }

        var courseId = CourseId.of("2");
        Flux.from(queryGateway.subscriptionQuery(new GetCourseStatsById(courseId), CoursesQueryResult.class))
            .doOnNext(next -> {
                log.info("[FACULTY] Course stats received: {}", next.courseStats());
            }).doOnError(e -> {
                log.error("[FACULTY] Error getting course stats.", e);
            }).subscribe();

        var randomCapacity = new Random().nextInt(25 - 5 + 1) + 5;

        log.info("[FACULTY] Changing capacity of course '{}' to {}.", courseId, randomCapacity);
        commandGateway.sendAndWait(new ChangeCourseCapacity(courseId, randomCapacity));


        log.info("[FACULTY] Faculty initialized.");
    }
}
