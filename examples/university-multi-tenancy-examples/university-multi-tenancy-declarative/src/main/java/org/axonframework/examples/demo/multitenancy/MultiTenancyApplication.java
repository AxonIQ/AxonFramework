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
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.demo.multitenancy.shared.run.DemoApplication;
import org.axonframework.examples.demo.multitenancy.shared.run.DemoLifecycle;
import org.axonframework.examples.demo.multitenancy.shared.run.DemoOutcome;
import org.axonframework.examples.demo.multitenancy.shared.tenant.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.shared.run.ProviderAmbiguityGuardrail;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantComponents;
import org.axonframework.examples.demo.multitenancy.shared.tenant.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstraps the multi-tenancy demo through the declarative Configuration API.
 * <p>
 * This class is only the assembly and the choice of tenant backing. The tenant lifecycle it runs, the
 * feature it demonstrates, lives in {@link DemoLifecycle} in the shared module, so this declarative
 * demo and the Spring Boot demo prove the exact same behavior and differ only in how the application is
 * configured. Here that is {@link UniversityConfiguration} on an {@link EventSourcingConfigurer}.
 * <p>
 * The same lifecycle runs two ways, selected by the {@code demo.axon-server.enabled} toggle: in memory by
 * default (tenants from a {@link DemoTenantProvider}), or against Axon Server (tenants are real
 * contexts). Which backing a run uses is the only choice each entry point makes.
 */
public class MultiTenancyApplication {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenancyApplication.class);

    /**
     * Entry point running the demo end to end, in memory by default or against Axon Server when the
     * {@code demo.axon-server.enabled} property is set.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        DemoProperties properties = DemoProperties.load();
        logger.info("Two providers for one component type are rejected at configuration time: {}",
                    ProviderAmbiguityGuardrail.rejectsTwoProvidersForOneType());
        MultiTenancyApplication demo = new MultiTenancyApplication();
        DemoOutcome outcome = properties.axonServerEnabled() ? demo.runWithAxonServer() : demo.run();
        logger.info("Demo finished. Outcome: {}", outcome);
    }

    /**
     * Runs the in-memory demo end to end, with the tenants supplied by an in-memory
     * {@link DemoTenantProvider}, and returns what it observed so the smoke test can assert the outcome
     * through the same entry point a user runs.
     *
     * @return the observed outcome of the demo run
     */
    public DemoOutcome run() {
        DemoTenantProvider tenantProvider =
                new DemoTenantProvider(DemoLifecycle.SPRINGFIELD, DemoLifecycle.SHELBYVILLE);
        TenantComponentProvider<CourseStatisticsStore> statisticsProvider = TenantComponents.courseStatisticsProvider();
        TenantComponentProvider<AuditLog> auditProvider = TenantComponents.auditLogProvider();

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        UniversityConfiguration.configure(configurer, tenantProvider, statisticsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();
        configuration.start();

        return DemoLifecycle.run(DemoApplication.inMemory(configuration,
                                                          tenantProvider,
                                                          statisticsProvider,
                                                          auditProvider));
    }

    /**
     * Runs the demo end to end against Axon Server, sourcing the tenants from Axon Server contexts
     * rather than from an in-memory provider. The lifecycle is identical to {@link #run()}, and the
     * backing is the only difference. This path needs a running multi-context (Enterprise Edition)
     * Axon Server, reachable on its default {@code localhost} address.
     *
     * @return the observed outcome of the demo run
     */
    public DemoOutcome runWithAxonServer() {
        TenantComponentProvider<CourseStatisticsStore> statisticsProvider = TenantComponents.courseStatisticsProvider();
        TenantComponentProvider<AuditLog> auditProvider = TenantComponents.auditLogProvider();

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        UniversityConfiguration.configureForAxonServer(configurer, statisticsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();
        configuration.start();

        return DemoLifecycle.run(DemoApplication.axonServer(configuration,
                                                           statisticsProvider,
                                                           auditProvider,
                                                           configuration::shutdown));
    }
}
