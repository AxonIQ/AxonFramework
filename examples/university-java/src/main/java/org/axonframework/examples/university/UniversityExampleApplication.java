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

import io.axoniq.framework.axonserver.connector.api.AxonServerConfiguration;
import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.infra.FilesystemStyleComponentDescriptor;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.university.read.coursestats.projection.CourseStatsConfiguration;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.UnaryOperator;

import static org.axonframework.common.ProcessUtils.executeUntilTrue;

/**
 * Main application class.
 */
public class UniversityExampleApplication {

    private static final String CONTEXT = "default";
    private static final Logger logger = LoggerFactory.getLogger(UniversityExampleApplication.class);
    /**
     * Starts the application
     *
     * @param args command line args.
     */
    public static void main(String[] args) {
        AxonConfiguration configuration = null;
        try {
            configuration = startApplication(ConfigurationProperties.load());
            var facultyInitializer = new FacultyInitializer(
                    configuration.getComponent(CommandGateway.class),
                    configuration.getComponent(QueryGateway.class)
            );

            var processor = configuration.getComponents(StreamingEventProcessor.class).get(CourseStatsConfiguration.NAME);
            logger.info("Waiting for course statistics projection replay to finish...");
            executeUntilTrue(
                    () -> !processor.isReplaying(),
                    1_000,
                    30
            );

            facultyInitializer.run();

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            if (configuration != null) {
                configuration.shutdown();
            }
        }
    }


    static AxonConfiguration startApplication(ConfigurationProperties configProps) {
        var configurer = new UniversityExampleApplication().configurer(configProps,
                                                                       FacultyModuleConfiguration::configure);
        var configuration = configurer.start();
        printApplicationConfiguration(configuration);
        return configuration;
    }

    static void printApplicationConfiguration(AxonConfiguration configuration) {
        var logger = LoggerFactory.getLogger(FacultyModuleConfiguration.class);
        var componentDescriptor = new FilesystemStyleComponentDescriptor();
        componentDescriptor.describeProperty("configuration", configuration);
        logger.debug("Application started with following configuration: {}", componentDescriptor.describe());
    }


    EventSourcingConfigurer configurer(
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
}
