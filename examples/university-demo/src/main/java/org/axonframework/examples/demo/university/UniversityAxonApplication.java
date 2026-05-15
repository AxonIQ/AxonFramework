package org.axonframework.examples.demo.university;

import io.axoniq.framework.axonserver.connector.api.AxonServerConfiguration;
import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import org.axonframework.examples.demo.university.faculty.FacultyModuleConfiguration;
import org.axonframework.examples.demo.university.faculty.write.enrollstudent.EnrollStudentInFaculty;
import org.axonframework.examples.demo.university.faculty.write.subscribestudent.SubscribeStudentToCourse;
import org.axonframework.examples.demo.university.shared.ids.CourseId;
import org.axonframework.examples.demo.university.faculty.write.createcourseplain.CreateCourse;
import org.axonframework.examples.demo.university.faculty.write.renamecourse.RenameCourse;
import org.axonframework.examples.demo.university.shared.ids.StudentId;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.common.infra.FilesystemStyleComponentDescriptor;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UniversityAxonApplication {

    private static final String CONTEXT = "default";
    private static final Logger logger = Logger.getLogger(UniversityAxonApplication.class.getName());

    public static void main(String[] args) {
        ConfigurationProperties configProps = ConfigurationProperties.load();
        var configuration = startApplication(configProps);
        executeSampleCommands(configuration);
        configuration.shutdown();
    }

    public static AxonConfiguration startApplication() {
        return startApplication(ConfigurationProperties.load());
    }

    public static AxonConfiguration startApplication(ConfigurationProperties configProps) {
        var configurer = new UniversityAxonApplication().configurer(configProps, FacultyModuleConfiguration::configure);
        var configuration = configurer.start();
        printApplicationConfiguration(configuration);
        return configuration;
    }

    private static void printApplicationConfiguration(AxonConfiguration configuration) {
        var componentDescriptor = new FilesystemStyleComponentDescriptor();
        componentDescriptor.describeProperty("configuration", configuration);
        logger.info("Application started with following configuration: \n" + componentDescriptor.describe());
    }

    public EventSourcingConfigurer configurer() {
        return configurer(ConfigurationProperties.load(), FacultyModuleConfiguration::configure);
    }

    public EventSourcingConfigurer configurer(
            ConfigurationProperties configProps,
            UnaryOperator<EventSourcingConfigurer> customization
    ) {
        var configurer = EventSourcingConfigurer.create();
        if (configProps.axonServerEnabled) {
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

    private static void executeSampleCommands(AxonConfiguration configuration) {
        try {
            var studentId1 = StudentId.random();
            var studentId2 = StudentId.random();
            var courseId = CourseId.random();
            var createCourse = new CreateCourse(courseId, "Event Sourcing in Practice", 2);
            var enrollStudent1 = new EnrollStudentInFaculty(studentId1, "Student", "One");
            var enrollStudent2 = new EnrollStudentInFaculty(studentId2, "Student", "Two");
            var renameCourse = new RenameCourse(courseId, "Advanced Event Sourcing");
            var subscribeStudentToCourse1 = new SubscribeStudentToCourse(studentId1, courseId);
            var subscribeStudentToCourse2 = new SubscribeStudentToCourse(studentId2, courseId);

            var commandGateway = configuration.getComponent(CommandGateway.class);
            commandGateway.sendAndWait(enrollStudent1);
            commandGateway.sendAndWait(enrollStudent2);
            commandGateway.sendAndWait(createCourse);
            commandGateway.sendAndWait(renameCourse);
            commandGateway.sendAndWait(subscribeStudentToCourse1);
            commandGateway.sendAndWait(subscribeStudentToCourse2);

            logger.info("Successfully executed sample commands");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while executing sample commands: " + e.getMessage(), e);
        }
    }

}
