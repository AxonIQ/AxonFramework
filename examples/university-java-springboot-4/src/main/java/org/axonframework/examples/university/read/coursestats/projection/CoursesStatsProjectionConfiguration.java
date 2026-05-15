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

package org.axonframework.examples.university.read.coursestats.projection;

import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoursesStatsProjectionConfiguration {

    public static String PROCESSOR_NAME = "courses-processor";

    @Bean
    CourseStatsRepository courseStatsRepository() {
        return new InMemoryCourseStatsRepository();
    }

    @Bean
    EventProcessorDefinition coursesProcessor() {
        return EventProcessorDefinition
                .pooledStreaming(PROCESSOR_NAME)
                .assigningHandlers(descriptor -> descriptor.beanType().getPackageName()
                                                           .endsWith("coursestats.projection"))
                .customized(config -> config
                        .initialSegmentCount(4)
                        .batchSize(10)
                );
    }
}
