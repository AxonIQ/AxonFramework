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
import io.axoniq.platform.framework.AxoniqPlatformConfiguration;
import org.awaitility.Awaitility;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogModuleConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CatalogViewReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.CourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.EnrolmentReadModel;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView;
import org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.WelcomeMessageView;
import org.axonframework.examples.demo.coursecatalog.catalog.seed.SeedCatalog;
import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent.EnrollStudent;
import org.axonframework.examples.demo.coursecatalog.catalog.write.publishcourse.PublishCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.write.updatecoursecapacity.UpdateCourseCapacity;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.Metadata;
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
        if (configProps.platformEnabled()) {
            configurer.componentRegistry(r -> r.registerComponent(
                    AxoniqPlatformConfiguration.class,
                    c -> new AxoniqPlatformConfiguration(
                            configProps.platformEnvironmentId(),
                            configProps.platformAccessToken(),
                            configProps.platformApplicationName())));
            logger.info("AxonIQ Platform reporting enabled for application '{}'", configProps.platformApplicationName());
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
        if (keepAlive) {
            // Force exit: non-daemon framework threads otherwise keep the JVM alive.
            System.exit(0);
        }
    }

    private static void seedHistoricEvents(AxonConfiguration configuration) {
        logger.info("Seeding historic events (idempotent)...");
        configuration.getComponent(CommandGateway.class).sendAndWait(new SeedCatalog(Ids.CATALOG_ID));
    }

    private static void runSampleCommands(AxonConfiguration configuration) {
        logger.info("Dispatching sample commands...");
        var commandGateway = configuration.getComponent(CommandGateway.class);

        // Publish a brand-new course (current shape v3) and update its capacity.
        CourseId courseId = CourseId.of("microservices-101");
        commandGateway.sendAndWait(new PublishCourse(courseId, "Microservices 101", new CapacityRange(5, 25)));
        commandGateway.sendAndWait(new UpdateCourseCapacity(courseId, new CapacityRange(10, 30)));

        // Enrol a historic student with no region in scope: the enrolment falls back to GLOBAL.
        commandGateway.sendAndWait(new EnrollStudent(courseId, StudentId.of("alice")));
        // Enrol another historic student into a historic course, this time with a region carried as
        // metadata: the interceptor lifts it onto the ProcessingContext, so StudentRegisteredV2ToV3
        // backfills "EU" while sourcing the student, and the enrolment carries it into the view.
        commandGateway.send(new EnrollStudent(CourseId.of("hexagonal-architecture"), StudentId.of("bob")),
                            Metadata.with(RequestRegion.METADATA_KEY, "EU"))
                      .wait(Object.class);
    }

    private static void awaitProjectionCatchUp(AxonConfiguration configuration) {
        // Expect 5 historic CoursePublished + 1 sample published course = 6 courses
        // in the view, the 1 seeded system announcement, and the 2 sample enrolments
        // (waiting for both so the printed view shows the region backfilled for the second one).
        Awaitility.await("catalog projection catch-up")
                  .atMost(Duration.ofSeconds(10))
                  .pollInterval(Duration.ofMillis(100))
                  .until(() -> {
                      CourseCatalogView v = queryView(configuration);
                      logger.debug("Waiting for projection: courses={}, enrolments={}, announcements={}, "
                                           + "registeredStudents={}, welcomeMessages={}",
                                   v.courses().size(), v.enrolments().size(), v.announcements().size(),
                                   v.registeredStudents(), v.welcomeMessages().size());
                      return v.courses().size() >= 6
                              && v.enrolments().size() >= 2
                              && !v.announcements().isEmpty()
                              && v.registeredStudents() >= 4
                              && v.welcomeMessages().size() >= 3;
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
        report.append("Enrolments (").append(view.enrolments().size()).append("):\n");
        for (EnrolmentReadModel enrolment : view.enrolments()) {
            report.append("  - ").append(enrolment.studentId().toString())
                  .append(" in ").append(enrolment.courseId().toString())
                  .append(" region=").append(enrolment.region())
                  .append('\n');
        }
        report.append("Announcements (").append(view.announcements().size()).append("):\n");
        for (String announcement : view.announcements()) {
            report.append("  - ").append(announcement).append('\n');
        }
        report.append("Welcome messages (").append(view.welcomeMessages().size()).append("):\n");
        for (WelcomeMessageView message : view.welcomeMessages()) {
            report.append("  - ").append(message.studentId()).append(": ").append(message.body()).append('\n');
        }
        report.append("System heartbeats seen by the projection: ").append(view.heartbeatsSeen())
              .append(" (pings are stored but dropped on read, so none are processed)\n");
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
