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

import org.axonframework.examples.demo.coursecatalog.ConfigurationProperties;
import org.axonframework.examples.demo.coursecatalog.CourseCatalogApplication;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;

import java.util.function.UnaryOperator;

/**
 * Bootstraps {@link AxonTestFixture}s for catalog tests. Always uses the in-memory
 * event store so tests stay fast and deterministic, regardless of what the bootstrap
 * application's {@code application.properties} says about Axon Server.
 *
 * <p>Use {@link #app()} for cross-slice integration tests, and
 * {@link #slice(UnaryOperator)} for single-slice tests so a {@code given().event(...)}
 * only flows through that slice's handlers.
 */
public final class CourseCatalogAxonTestFixture {

    private CourseCatalogAxonTestFixture() {
    }

    /**
     * Shared infrastructure (transformation chain) plus every slice.
     *
     * @return a fixture wired with the full catalog module
     */
    public static AxonTestFixture app() {
        return build(CourseCatalogModuleConfiguration::configure);
    }

    /**
     * Shared infrastructure plus only the slice configured by {@code sliceConfig}.
     * Use this for slice tests so the test is hermetic.
     *
     * @param sliceConfig the slice's {@code Configuration::configure} reference
     * @return a fixture wired with shared infra + the given slice
     */
    public static AxonTestFixture slice(UnaryOperator<EventSourcingConfigurer> sliceConfig) {
        return slice(sliceConfig, c -> c);
    }

    /**
     * Shared infrastructure, a caller-supplied customization, and only the slice configured
     * by {@code sliceConfig}. The customization runs BEFORE the slice's configure, so any
     * components it registers win against the slice's {@code registerIfNotPresent} defaults
     * (typical use: inject a recording adapter in place of a logging one).
     *
     * @param sliceConfig   the slice's {@code Configuration::configure} reference
     * @param customization extra wiring chained on top of shared infra and before the slice
     * @return a fixture wired with shared infra + customization + the given slice
     */
    public static AxonTestFixture slice(
            UnaryOperator<EventSourcingConfigurer> sliceConfig,
            UnaryOperator<EventSourcingConfigurer> customization
    ) {
        return build(c -> sliceConfig.apply(
                customization.apply(CourseCatalogModuleConfiguration.configureSharedInfra(c))
        ));
    }

    private static AxonTestFixture build(UnaryOperator<EventSourcingConfigurer> setup) {
        var configurer = new CourseCatalogApplication()
                .configurer(ConfigurationProperties.defaults(), setup);
        return AxonTestFixture.with(configurer);
    }
}
