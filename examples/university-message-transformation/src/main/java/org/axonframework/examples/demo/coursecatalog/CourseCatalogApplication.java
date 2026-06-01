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

package org.axonframework.examples.demo.coursecatalog;

import io.axoniq.framework.axonserver.connector.api.AxonServerConfiguration;
import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import org.awaitility.Awaitility;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogModuleConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CatalogViewReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.seed.SeedCatalog;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse.PublishCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity.UpdateCourseCapacity;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Bootstraps the course-catalog demo. Builds an {@link EventSourcingConfigurer} with
 * Axon Server connection toggled by {@link ConfigurationProperties#axonServerEnabled()}
 * and the catalog module wired in. The {@code main()} entry point seeds historic events,
 * runs a few sample commands, prints the resulting catalog view, then shuts down.
 */
public class CourseCatalogApplication {

    private static final Logger logger = LoggerFactory.getLogger(CourseCatalogApplication.class);
    private static final String CONTEXT = "default";

    /**
     * @param configProps   runtime configuration toggles
     * @param customization additional wiring applied on top of the catalog defaults
     * @return the configured {@link EventSourcingConfigurer}
     */
    public EventSourcingConfigurer configurer(
            ConfigurationProperties configProps,
            UnaryOperator<EventSourcingConfigurer> customization
    ) {
        var configurer = EventSourcingConfigurer.create();
        if (configProps.axonServerEnabled()) {
            configurer.componentRegistry(r -> r.registerComponent(AxonServerConfiguration.class, c -> {
                var axonServerConfig = new AxonServerConfiguration();
                axonServerConfig.setContext(CONTEXT);
                return axonServerConfig;
            }));
        } else {
            configurer.componentRegistry(r -> r.disableEnhancer(AxonServerConfigurationEnhancer.class));
        }
        configurer = customization.apply(configurer);
        return configurer;
    }

    /**
     * Entry point: starts the configurer, seeds historic events, dispatches a few
     * sample commands, waits for the projection to catch up, prints the catalog view,
     * then either shuts down or, if {@code --keep-alive} is passed, drops the user into
     * an {@link InteractiveShell} so commands can still be dispatched against the
     * running application.
     *
     * @param args supports {@code --keep-alive} to keep the JVM running with a stdin REPL
     */
    public static void main(String[] args) {
        boolean keepAlive = false;
        for (String arg : args) {
            if ("--keep-alive".equals(arg)) {
                keepAlive = true;
                break;
            }
        }
        ConfigurationProperties props = ConfigurationProperties.load();
        AxonConfiguration configuration = new CourseCatalogApplication()
                .configurer(props, CourseCatalogModuleConfiguration::configure)
                .start();
        try {
            seedHistoricEvents(configuration);
            runSampleCommands(configuration);
            awaitProjectionCatchUp(configuration);
            printCatalogView(configuration);
            if (keepAlive) {
                InteractiveShell.run(configuration);
            }
        } finally {
            configuration.shutdown();
        }
    }

    private static void seedHistoricEvents(AxonConfiguration configuration) {
        logger.info("Seeding historic events (idempotent)...");
        configuration.getComponent(CommandGateway.class).sendAndWait(new SeedCatalog(Ids.CATALOG_ID));
    }

    private static void runSampleCommands(AxonConfiguration configuration) {
        logger.info("Dispatching sample commands...");
        var commandGateway = configuration.getComponent(CommandGateway.class);

        // Publish a brand-new course (current shape v3), update its capacity, enrol a student.
        CourseId courseId = CourseId.of("microservices-101");
        StudentId studentId = StudentId.of("alice");
        commandGateway.sendAndWait(new PublishCourse(courseId, "Microservices 101", new CapacityRange(5, 25)));
        commandGateway.sendAndWait(new UpdateCourseCapacity(courseId, new CapacityRange(10, 30)));
        commandGateway.sendAndWait(new EnrollStudent(courseId, studentId));
    }

    private static void awaitProjectionCatchUp(AxonConfiguration configuration) {
        // Expect 5 historic CoursePublished + 1 sample published course = 6 courses
        // in the view, plus the 1 system announcement seeded.
        Awaitility.await("catalog projection catch-up")
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(100))
                  .until(() -> {
                      CourseCatalogView v = queryView(configuration);
                      logger.debug("Waiting for projection: courses={}, announcements={}, registeredStudents={}",
                                   v.courses().size(), v.announcements().size(), v.registeredStudents());
                      return v.courses().size() >= 6
                              && !v.announcements().isEmpty()
                              && v.registeredStudents() >= 4;
                  });
    }

    private static void printCatalogView(AxonConfiguration configuration) {
        CourseCatalogView view = queryView(configuration);
        StringBuilder report = new StringBuilder();
        report.append("\n--- Course Catalog View ---\n");
        report.append("Registered students: ").append(view.registeredStudents()).append('\n');
        report.append("Courses (").append(view.courses().size()).append("):\n");
        for (CatalogViewReadModel course : view.courses()) {
            report.append("  - ").append(course.courseId().toString())
                  .append(" \"").append(course.name()).append("\"")
                  .append(" range=").append(course.range())
                  .append(" enrolments=").append(course.enrolments())
                  .append(course.registrationClosed() ? " [closed]" : "")
                  .append('\n');
        }
        report.append("Announcements (").append(view.announcements().size()).append("):\n");
        for (String announcement : view.announcements()) {
            report.append("  - ").append(announcement).append('\n');
        }
        String reportAsString = report.toString();
        logger.info(reportAsString);
    }

    private static CourseCatalogView queryView(AxonConfiguration configuration) {
        return configuration.getComponent(QueryGateway.class)
                            .query(new GetCourseCatalogView(), CourseCatalogView.class, null)
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join();
    }

}
