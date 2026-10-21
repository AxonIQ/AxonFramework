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

package org.axonframework.extension.metrics.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import java.util.Objects;

/**
 * Configuration container for the {@link MetricsConfigurationEnhancer}, containing the {@link MeterRegistry} and
 * whether to add dimensionality to those measurements.
 * <p>
 * Should be registered with the {@link org.axonframework.common.configuration.ComponentRegistry} to ensure other
 * components can pick it up when required.
 *
 * @param registry      the {@link MeterRegistry} which will record the metrics
 * @param useDimensions when {@code true}, {@link Tag tags} are added to measurements
 * @author Steven van Beelen
 * @since 5.5.0
 */
public record MicrometerMetricsConfiguration(
        MeterRegistry registry,
        boolean useDimensions
) {

    /**
     * Constructs a {@code MicrometerMetricsConfiguration}.
     *
     * @param registry      the {@link MeterRegistry} which will record the metrics
     * @param useDimensions when {@code true}, {@link Tag tags} are added to measurements
     */
    public MicrometerMetricsConfiguration {
        Objects.requireNonNull(registry, "The MeterRegistry must not be null.");
    }
}
