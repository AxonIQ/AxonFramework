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

package org.axonframework.extension.micronaut.autoconfig;

import org.axonframework.extension.micronaut.config.EventProcessorSettings;
import org.axonframework.extension.micronaut.EventProcessorProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Auto configuration for event processors.
 *
 * @author Milan Savic
 * @author Simon Zambrovski
 * @since 4.0
 */
@AutoConfiguration
@EnableConfigurationProperties(EventProcessorProperties.class)
public class EventProcessingAutoConfiguration {

    /**
     * Constructs event processing settings.
     *
     * @param properties event processor properties.
     * @return event processor settings keyed by processor name.
     */
    @Bean
    public EventProcessorSettings.MapWrapper eventProcessorSettings(@Lazy EventProcessorProperties properties) {

        Map<String, EventProcessorSettings> map = properties
                .getProcessors()
                .entrySet()
                .stream()
                .collect(
                        Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                java.util.Map.Entry::getValue
                        )
                );
        // supply a default setting
        // this gives us the location for the default values, and allows to overwrite them
        // via properties by the user.
        if (!map.containsKey(EventProcessorSettings.DEFAULT)) {
            map.put(EventProcessorSettings.DEFAULT, new EventProcessorProperties.ProcessorSettings());
        }
        return new EventProcessorSettings.MapWrapper(map);
    }
}
