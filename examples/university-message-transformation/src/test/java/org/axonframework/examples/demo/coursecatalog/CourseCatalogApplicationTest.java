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

import io.axoniq.framework.testcontainer.AxonServerContainerUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogModuleConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;

/**
 * Shared base for application-level tests. Boots the real configurer (catalog
 * module wired in), purges Axon Server between tests if enabled, and exposes the
 * resulting {@link AxonConfiguration} to subclasses.
 */
public abstract class CourseCatalogApplicationTest {

    protected AxonConfiguration configuration;

    @BeforeEach
    void beforeEach() {
        ConfigurationProperties configurationProperties = overrideProperties(ConfigurationProperties.load());
        purgeAxonServerIfEnabled(configurationProperties);
        EventSourcingConfigurer configurer = new CourseCatalogApplication()
                .configurer(configurationProperties, this::overrideConfigurer);
        this.configuration = configurer.start();
    }

    @AfterEach
    void afterEach() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    private static void purgeAxonServerIfEnabled(ConfigurationProperties props) {
        if (props.axonServerEnabled()) {
            try {
                AxonServerContainerUtils.purgeEventsFromAxonServer("localhost", 8024, "default", true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Override to tweak loaded properties before the configurer is built. */
    protected ConfigurationProperties overrideProperties(ConfigurationProperties properties) {
        return properties;
    }

    /** Defaults to the full catalog module; override to test a narrower slice. */
    protected EventSourcingConfigurer overrideConfigurer(EventSourcingConfigurer configurer) {
        return CourseCatalogModuleConfiguration.configure(configurer);
    }
}
