package org.axonframework.examples.demo.university.faculty;

import io.axoniq.framework.testcontainer.AxonServerContainerUtils;
import org.axonframework.examples.demo.university.ConfigurationProperties;
import org.axonframework.examples.demo.university.UniversityAxonApplication;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.demo.university.faculty.write.createcourse.CreateCourseConfiguration;
import org.axonframework.test.extension.AxonTestFixtureProvider;
import org.axonframework.test.fixture.AxonTestFixture;

import java.io.IOException;
import java.util.function.UnaryOperator;

public class FacultyAxonTestFixture {

    public static class CreateCourseConfigurationFixture implements AxonTestFixtureProvider {

        @Override
        public AxonTestFixture get() {
            return slice(CreateCourseConfiguration::configure);
        }
    }

    public static class FacultyModuleConfigurationFixture implements AxonTestFixtureProvider {

        @Override
        public AxonTestFixture get() {
            return app();
        }
    }

    public static AxonTestFixture app() {
        return slice(FacultyModuleConfiguration::configure);
    }

    public static AxonTestFixtureProvider sliceProvider(UnaryOperator<EventSourcingConfigurer> customization) {
        var application = new UniversityAxonApplication();
        var configuration = ConfigurationProperties.load();
        var configurer = application.configurer(configuration, customization);
        purgeAxonServerIfEnabled(configuration);
        return () -> AxonTestFixture.with(configurer, c -> configuration.axonServerEnabled() ? c.asIntegrationTest() : c);
    }

    public static AxonTestFixture slice(UnaryOperator<EventSourcingConfigurer> customization) {
        return sliceProvider(customization).get();
    }

    private static void purgeAxonServerIfEnabled(ConfigurationProperties configuration) {
        boolean axonServerEnabled = configuration.axonServerEnabled();
        if (axonServerEnabled) {
            try {
                AxonServerContainerUtils.purgeEventsFromAxonServer("localhost", 8024, "default", true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private FacultyAxonTestFixture() {

    }
}
