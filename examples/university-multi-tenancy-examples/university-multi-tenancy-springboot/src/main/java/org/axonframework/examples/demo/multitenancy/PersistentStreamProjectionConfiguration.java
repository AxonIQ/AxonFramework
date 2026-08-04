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

package org.axonframework.examples.demo.multitenancy;

import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.StatisticsConfiguration;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the course-statistics projection processor as a subscribing processor fed by a persistent stream,
 * instead of {@link PooledStreamingProjectionConfiguration}'s default pooled streaming processor. Active while
 * multi-tenancy and Axon Server are both on and {@code axon.axonserver.auto-persistent-streams-enabled=true}.
 * <p>
 * Guarded once at class level: the one bean here is the only thing this variant needs, and none of
 * {@link PooledStreamingProjectionConfiguration}'s beans apply to it.
 */
@Configuration
@ConditionalOnExpression("${axon.multitenancy.enabled:true} and ${axon.axonserver.enabled:true}")
@ConditionalOnProperty(name = "axon.axonserver.auto-persistent-streams-enabled", havingValue = "true")
public class PersistentStreamProjectionConfiguration {

    /**
     * The subscribing processor that projects every tenant's events over a persistent stream, running the same
     * {@code UniversityConfiguration.courseStatisticsProjection()} bean.
     * <p>
     * No event source is set here: {@code axon.axonserver.auto-persistent-streams-enabled} (see
     * {@code application.yml}) is what makes the starter fabricate one named after this processor, and because
     * multi-tenancy is active, it builds that stream through the multi-tenant persistent stream support, one
     * ordinary stream per tenant fanned into this single processor. That one property therefore both activates
     * this configuration and supplies the processor's event source. Unlike the pooled streaming variant, this
     * processor needs no token store, since a subscribing processor tracks no position at all.
     *
     * @return the course-statistics projection processor definition, run on a persistent stream
     */
    @Bean
    public EventProcessorDefinition courseStatisticsPersistentStreamProcessor() {
        return EventProcessorDefinition
                .subscribing(StatisticsConfiguration.PROCESSOR_NAME)
                .assigningHandlers(descriptor -> CourseStatisticsProjection.class.equals(descriptor.beanType()))
                .notCustomized();
    }
}
