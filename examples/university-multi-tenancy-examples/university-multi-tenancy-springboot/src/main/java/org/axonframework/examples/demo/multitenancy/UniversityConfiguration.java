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

import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.axonserver.api.AxonServerTenantProvider;
import io.axoniq.framework.messaging.multitenancy.configuration.MultiTenantStreamingProcessorRestartConfiguration;
import org.axonframework.common.configuration.Module;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantComponents;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsProjection;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.StatisticsConfiguration;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatisticsQueryHandler;
import org.axonframework.examples.demo.multitenancy.university.write.enrollstudent.EnrollStudentConfiguration;
import org.axonframework.examples.demo.multitenancy.university.write.opencourse.OpenCourseConfiguration;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The whole multi-tenancy configuration a Spring Boot developer writes for the feature: declare one
 * {@link TenantComponentProvider} bean per tenant-scoped component type, the statistics query handler,
 * and each write slice's modules, as ordinary beans.
 * <p>
 * Multi-tenancy is active because the {@code axoniq-multi-tenancy} module is on the classpath. The
 * auto-configuration from the Axoniq Framework Spring Boot starter only switches it off again when
 * {@code axon.multitenancy.enabled=false} or {@code axon.axonserver.enabled=false}, since tenants are Axon
 * Server contexts. The framework picks these provider beans up, subscribes them to the tenant lifecycle,
 * and installs the tenant parameter resolver and interceptor. It also registers the default
 * auto-discovering {@link AxonServerTenantProvider}, which watches Axon Server's contexts and registers
 * each as a tenant (filtering out {@code _admin}). So
 * the framework hands each command and query handler the components of the message's tenant for their
 * {@code @TenantScoped} parameters, matched by type, with no explicit multi-tenancy wiring here at all.
 */
@Configuration
public class UniversityConfiguration {

    // The framework's own default.
    private static final Duration PROCESSOR_RESTART_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The provider of per-tenant {@link CourseStatisticsStore} instances.
     *
     * @return the tenant-scoped course-statistics provider
     */
    @Bean
    public TenantComponentProvider<CourseStatisticsStore> courseStatisticsProvider() {
        return TenantComponents.courseStatisticsProvider();
    }

    /**
     * The provider of per-tenant {@link AuditLog} instances.
     *
     * @return the tenant-scoped audit-log provider
     */
    @Bean
    public TenantComponentProvider<AuditLog> auditLogProvider() {
        return TenantComponents.auditLogProvider();
    }

    /**
     * A single shared {@link SnapshotStore}, for runs where multi-tenancy is switched off.
     * <p>
     * The course carries a snapshot policy, so a snapshot store has to be present or the configuration
     * fails to start. While multi-tenancy is active the defaults supply one per tenant, so this bean must
     * not exist then: a store the application registers itself is refused.
     * <p>
     * Tenants are Axon Server contexts, so the feature is inactive whenever either
     * {@code axon.multitenancy.enabled} or {@code axon.axonserver.enabled} is off, and this bean covers both.
     * The condition has to be property-based rather than {@code @ConditionalOnMissingBean}, because the
     * per-tenant registration happens in Axon's component registry rather than in the bean factory, so no
     * bean exists for Spring to find.
     *
     * @return one snapshot store shared by the whole application
     */
    @Bean
    @ConditionalOnExpression("!${axon.multitenancy.enabled:true} or !${axon.axonserver.enabled:true}")
    public SnapshotStore snapshotStore() {
        return new InMemorySnapshotStore();
    }

    /**
     * The statistics query handler, whose {@code @TenantScoped} parameters the framework injects with
     * the message tenant's per-tenant components, matched by type.
     *
     * @return the statistics query handler
     */
    @Bean
    public TenantStatisticsQueryHandler tenantStatisticsQueryHandler() {
        return new TenantStatisticsQueryHandler();
    }

    /**
     * The open-course slice's event-sourced entity module, which the starter registers so a course can be
     * sourced from its tenant's own event store.
     *
     * @return the open-course entity module
     */
    @Bean
    public Module openCourseEntity() {
        return OpenCourseConfiguration.entityModule();
    }

    /**
     * The open-course slice's command handling module, holding the handler that opens courses.
     *
     * @return the open-course command handling module
     */
    @Bean
    public Module openCourseCommandHandling() {
        return OpenCourseConfiguration.commandModule();
    }

    /**
     * The enroll-student slice's event-sourced entity module, sourced from and appended to the tenant's
     * own event store.
     *
     * @return the enroll-student entity module
     */
    @Bean
    public Module enrollStudentEntity() {
        return EnrollStudentConfiguration.entityModule();
    }

    /**
     * The enroll-student slice's command handling module. The demonstration runs against Axon Server, so the
     * read model is built by the {@link CourseStatisticsProjection} and this handler only appends to the
     * tenant's event store.
     * <p>
     * The backing is fixed rather than read from the properties. That only matters to the configuration which
     * switches multi-tenancy off, where a command is rejected before anything is appended anyway.
     *
     * @return the enroll-student command handling module
     */
    @Bean
    public Module enrollStudentCommandHandling() {
        return EnrollStudentConfiguration.commandModule(DemoBacking.AXON_SERVER);
    }

    /**
     * The projection building every tenant's course statistics from the enrollment events of every tenant.
     * <p>
     * An ordinary event handler bean. Its {@code @TenantScoped} parameters are injected with the components of
     * the tenant whose event store the event was streamed from.
     *
     * @return the course-statistics projection
     */
    @Bean
    @ConditionalOnExpression("${axon.multitenancy.enabled:true} and ${axon.axonserver.enabled:true}")
    public CourseStatisticsProjection courseStatisticsProjection() {
        return new CourseStatisticsProjection();
    }

    /**
     * The single token store tracking every tenant's position, which the projection processor needs.
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
    @ConditionalOnExpression("${axon.multitenancy.enabled:true} and ${axon.axonserver.enabled:true}")
    public TokenStore tokenStore() {
        return new InMemoryTokenStore();
    }

    /**
     * The one pooled streaming processor that projects every tenant's events, running the
     * {@link #courseStatisticsProjection()} bean.
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
    @ConditionalOnExpression("${axon.multitenancy.enabled:true} and ${axon.axonserver.enabled:true}")
    public EventProcessorDefinition courseStatisticsProcessor() {
        return EventProcessorDefinition
                .pooledStreaming(StatisticsConfiguration.PROCESSOR_NAME)
                .assigningHandlers(descriptor -> CourseStatisticsProjection.class.equals(descriptor.beanType()))
                .notCustomized();
    }

    /**
     * How long each processor gets to stop and start again when the set of tenants changes.
     * <p>
     * A tenant change restarts the running streaming event processors, and this bounds how long each one gets.
     * The starter already registers a default, so declare this only to raise it for a deployment whose
     * processors are slow to stop and start. Shown here at the default value.
     *
     * @return the restart configuration bounding each processor's restart
     */
    @Bean
    @ConditionalOnExpression("${axon.multitenancy.enabled:true} and ${axon.axonserver.enabled:true}")
    public MultiTenantStreamingProcessorRestartConfiguration processorRestartConfiguration() {
        return MultiTenantStreamingProcessorRestartConfiguration.DEFAULT.restartTimeout(PROCESSOR_RESTART_TIMEOUT);
    }
}
