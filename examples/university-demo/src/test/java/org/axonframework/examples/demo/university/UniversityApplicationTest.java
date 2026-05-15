package org.axonframework.examples.demo.university;

import io.axoniq.framework.testcontainer.AxonServerContainerUtils;
import org.axonframework.examples.demo.university.faculty.FacultyModuleConfiguration;
import org.assertj.core.api.Assertions;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.MessagesRecordingConfigurationEnhancer;
import org.axonframework.test.fixture.RecordingComponentsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.List;

public abstract class UniversityApplicationTest {

    protected AxonConfiguration configuration;

    @BeforeEach
    void beforeEach() {
        var configuration = ConfigurationProperties.load();
        var properties = overrideProperties(configuration);
        purgeAxonServerIfEnabled(configuration);
        var configurer = new UniversityAxonApplication().configurer(properties, this::configureTestApplication);
        this.configuration = configurer.start();
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

    @AfterEach
    void afterEach() {
        configuration.shutdown();
    }

    private EventSourcingConfigurer configureTestApplication(EventSourcingConfigurer configurer) {
        configurer = configurer.componentRegistry(cr -> cr.registerEnhancer(new MessagesRecordingConfigurationEnhancer()));
        configurer = overrideConfigurer(configurer);
        return configurer;
    }

    protected void eventOccurred(Object event) {
        eventsOccurred(event);
    }

    protected void eventsOccurred(Object... events) {
        eventsOccurred(List.of(events));
    }

    protected void eventsOccurred(List<Object> events) {
        var eventGateway = configuration.getComponent(EventGateway.class);
        var unitOfWork = configuration.getComponent(UnitOfWorkFactory.class).create();
        unitOfWork.onInvocation(ctx -> eventGateway.publish(ctx, events));
        unitOfWork.execute().join();
        var eventSink = configuration.getComponent(RecordingComponentsRegistry.class).eventSink();
        eventSink.reset();
    }

    protected void executeCommand(Object command) {
        var commandGateway = configuration.getComponent(CommandGateway.class);
        commandGateway.sendAndWait(command);
    }

    protected void assertEvents(Object... events) {
        var eventSink = configuration.getComponent(RecordingComponentsRegistry.class).eventSink();
        Assertions.assertThat(eventSink.recorded().stream().map(Message::payload)).contains(events);
    }

    protected void assertNoEvents() {
        var eventSink = configuration.getComponent(RecordingComponentsRegistry.class).eventSink();
        Assertions.assertThat(eventSink.recorded().stream().map(Message::payload)).isEmpty();
    }

    protected ConfigurationProperties overrideProperties(ConfigurationProperties properties) {
        return properties;
    }

    /**
     * By default, the whole Faculty Module is configured, but you can easily test only parts of it (certain app modules).
     *
     * @param configurer
     * @return
     */
    protected EventSourcingConfigurer overrideConfigurer(EventSourcingConfigurer configurer) {
        return FacultyModuleConfiguration.configure(configurer);
    }
}
