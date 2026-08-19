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

import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenantStreamingProcessorRestartConfiguration;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.StatisticsConfiguration;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Runs the course-statistics projection processor as the default pooled streaming processor, reading the
 * tenant-aware event store directly. Active while multi-tenancy and Axon Server are both on, and
 * {@code axon.axonserver.auto-persistent-streams-enabled} is not set to {@code true}; see
 * {@link PersistentStreamProjectionConfiguration} for the alternative that property switches to.
 * <p>
 * Guarded once at class level, rather than on each bean, since every bean here exists for this one variant
 * and none of them makes sense without the others.
 */
@Configuration
@ConditionalOnExpression("${axon.multitenancy.enabled:true} and ${axon.axonserver.enabled:true}")
@ConditionalOnProperty(name = "axon.axonserver.auto-persistent-streams-enabled",
                       havingValue = "false",
                       matchIfMissing = true)
public class PooledStreamingProjectionConfiguration {

    // The framework's own default.
    private static final Duration PROCESSOR_RESTART_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The single token store tracking every tenant's position, which the pooled streaming processor needs.
     * <p>
     * One store serves every tenant, holding one position per tenant in a single token. A real deployment would
     * use a persistent store so positions survive a restart.
     * <p>
     * The bean has to be named exactly {@code tokenStore}: Spring resolves a processor's token store by bean
     * name, defaulting to that, and fails when no such bean exists. The declarative demo needs no equivalent.
     *
     * @return the token store shared by every tenant
     */
    @Bean
    public TokenStore tokenStore() {
        return new InMemoryTokenStore();
    }

    /**
     * The pooled streaming processor that projects every tenant's events, running the
     * {@code UniversityConfiguration.courseStatisticsProjection()} bean, reading the tenant-aware event store
     * directly.
     * <p>
     * An ordinary processor definition: nothing here mentions a tenant, and none is defined per tenant. The
     * multi-tenancy autoconfiguration makes the event store it streams from tenant-aware, and re-opens that
     * stream when the set of tenants changes.
     * <p>
     * Declared through an {@link EventProcessorDefinition} because a {@code Module} bean holding an event
     * processor is silently ignored on this path.
     *
     * @return the course-statistics projection processor definition
     */
    @Bean
    public EventProcessorDefinition courseStatisticsProcessor() {
        return EventProcessorDefinition
                .pooledStreaming(StatisticsConfiguration.PROCESSOR_NAME)
                .assigningHandlers(descriptor -> CourseStatisticsProjection.class.equals(descriptor.beanType()))
                .notCustomized();
    }

    /**
     * How long the processor above gets to stop and start again when the set of tenants changes.
     * <p>
     * A tenant change restarts a running pooled streaming processor, and this bounds how long it gets. The
     * starter already registers a default, so declare this only to raise it for a deployment whose processor
     * is slow to stop and start. Shown here at the default value. Not needed by
     * {@link PersistentStreamProjectionConfiguration}: a persistent stream opens and closes each tenant's
     * share of it independently, so nothing there ever restarts on a tenant change.
     *
     * @return the restart configuration bounding the processor's restart
     */
    @Bean
    public MultiTenantStreamingProcessorRestartConfiguration processorRestartConfiguration() {
        return MultiTenantStreamingProcessorRestartConfiguration.DEFAULT.restartTimeout(PROCESSOR_RESTART_TIMEOUT);
    }
}
