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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.SearchScope;

/**
 * A {@link ConfigurationEnhancer} registering the default {@link MicrometerMetricsConfiguration}
 *
 * @author Steven van Beelen
 * @since 5.4.1
 */
public class MicrometerMetricsConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer, set to {@link MetricsConfigurationEnhancer#ENHANCER_ORDER} plus {@code 5},
     * ensuring the deprecated flow wherein the {@code MetricsConfigurationEnhancer} sets these itself still wins.
     * <p>
     * The fact we register components and decorators in an enhancer ensures a {@link MicrometerMetricsConfiguration}
     * will be present regardless of the path that's chosen by the user.
     */
    public static final int ENHANCER_ORDER = MetricsConfigurationEnhancer.ENHANCER_ORDER + 5;

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerIfNotPresent(
                MicrometerMetricsConfiguration.class,
                c -> new MicrometerMetricsConfiguration(new SimpleMeterRegistry(), true),
                SearchScope.ALL
        );
    }

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }
}
