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
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogModuleConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

import java.util.function.UnaryOperator;

/**
 * Bootstraps the course-catalog demo. Builds an {@link EventSourcingConfigurer} with
 * Axon Server connection toggled by {@link ConfigurationProperties#axonServerEnabled()}
 * and the catalog module wired in. The {@code main()} entry point (with seeding +
 * sample commands) lands in a later step.
 */
public class CourseCatalogApplication {

    private static final String CONTEXT = "default";

    /** @return the configurer wired with default properties and the full catalog module */
    public EventSourcingConfigurer configurer() {
        return configurer(ConfigurationProperties.load(), CourseCatalogModuleConfiguration::configure);
    }

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
}
