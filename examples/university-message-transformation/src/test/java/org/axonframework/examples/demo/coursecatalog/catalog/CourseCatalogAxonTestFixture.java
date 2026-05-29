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

package org.axonframework.examples.demo.coursecatalog.catalog;

import io.axoniq.framework.testcontainer.AxonServerContainerUtils;
import org.axonframework.examples.demo.coursecatalog.ConfigurationProperties;
import org.axonframework.examples.demo.coursecatalog.CourseCatalogApplication;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;

import java.io.IOException;
import java.util.function.UnaryOperator;

/**
 * Bootstraps {@link AxonTestFixture}s for per-slice tests. Loads the same
 * {@link ConfigurationProperties} the production application uses, applies the
 * catalog module configuration plus a caller-supplied per-slice customization,
 * and (when Axon Server is enabled) purges events between tests.
 */
public final class CourseCatalogAxonTestFixture {

    private CourseCatalogAxonTestFixture() {
    }

    /**
     * @return a fixture wired with the full catalog module
     */
    public static AxonTestFixture app() {
        return slice(c -> c);
    }

    /**
     * The catalog module (including the transformation chain) is always registered so
     * historic-shape events seeded via {@code fixture.given().event(...)} reach the
     * slice's command handler in the current shape.
     *
     * @param customization per-slice wiring chained on top of the catalog module
     * @return a fixture targeting the given slice
     */
    public static AxonTestFixture slice(UnaryOperator<EventSourcingConfigurer> customization) {
        // Apply the caller's customization BEFORE the catalog module so any component
        // the test registers (e.g. a RecordingNotificationService) is in place before
        // the catalog's registerIfNotPresent defaults run.
        UnaryOperator<EventSourcingConfigurer> withCatalog =
                c -> CourseCatalogModuleConfiguration.configure(customization.apply(c));
        var application = new CourseCatalogApplication();
        var configuration = ConfigurationProperties.load();
        var configurer = application.configurer(configuration, withCatalog);
        purgeAxonServerIfEnabled(configuration);
        return AxonTestFixture.with(
                configurer,
                c -> configuration.axonServerEnabled() ? c.asIntegrationTest() : c
        );
    }

    private static void purgeAxonServerIfEnabled(ConfigurationProperties configuration) {
        if (configuration.axonServerEnabled()) {
            try {
                AxonServerContainerUtils.purgeEventsFromAxonServer("localhost", 8024, "default", true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
