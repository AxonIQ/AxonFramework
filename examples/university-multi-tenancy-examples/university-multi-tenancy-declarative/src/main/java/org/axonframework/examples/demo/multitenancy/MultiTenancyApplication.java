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
import org.axonframework.examples.demo.multitenancy.shared.DemoLifecycle;
import org.axonframework.examples.demo.multitenancy.shared.DemoOutcome;
import org.axonframework.examples.demo.multitenancy.shared.DemoTenantProvider;
import org.axonframework.examples.demo.multitenancy.shared.ProviderAmbiguityGuardrail;
import org.axonframework.examples.demo.multitenancy.shared.TenantComponents;
import org.axonframework.examples.demo.multitenancy.shared.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatsStore;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstraps the multi-tenancy demo through the declarative Configuration API.
 * <p>
 * This class is only the assembly and the choice of tenant backing. The tenant lifecycle it runs, the
 * feature it demonstrates, lives in {@link DemoLifecycle} in the shared module, so this declarative
 * demo and the Spring Boot demo prove the exact same behavior and differ only in how the application is
 * configured. Here that is {@link UniversityConfiguration} on a {@link MessagingConfigurer}.
 * <p>
 * The same lifecycle runs two ways, selected by the {@code demo.axon-server.enabled} toggle: in memory by
 * default (tenants from a {@link DemoTenantProvider}), or against Axon Server (tenants are real
 * contexts). Only the {@link TenantProvisioning} changes.
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
        TenantComponentProvider<CourseStatsStore> statsProvider = TenantComponents.courseStatsProvider();
        TenantComponentProvider<AuditLog> auditProvider = TenantComponents.auditLogProvider();

        MessagingConfigurer configurer = MessagingConfigurer.create();
        UniversityConfiguration.configure(configurer, tenantProvider, statsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();
        configuration.start();

        return DemoLifecycle.run(configuration.getComponent(CommandGateway.class),
                                 configuration.getComponent(QueryGateway.class),
                                 statsProvider,
                                 auditProvider,
                                 TenantProvisioning.inMemory(tenantProvider),
                                 configuration::shutdown);
    }

    /**
     * Runs the demo end to end against Axon Server, sourcing the tenants from Axon Server contexts
     * rather than from an in-memory provider. The lifecycle is identical to {@link #run()}. Only the
     * {@link TenantProvisioning} differs. This path needs a running multi-context (Enterprise Edition)
     * Axon Server, reachable on its default {@code localhost} address.
     *
     * @return the observed outcome of the demo run
     */
    public DemoOutcome runWithAxonServer() {
        TenantComponentProvider<CourseStatsStore> statsProvider = TenantComponents.courseStatsProvider();
        TenantComponentProvider<AuditLog> auditProvider = TenantComponents.auditLogProvider();

        MessagingConfigurer configurer = MessagingConfigurer.create();
        UniversityConfiguration.configureForAxonServer(configurer, statsProvider, auditProvider);
        AxonConfiguration configuration = configurer.build();
        configuration.start();

        return DemoLifecycle.run(configuration.getComponent(CommandGateway.class),
                                 configuration.getComponent(QueryGateway.class),
                                 statsProvider,
                                 auditProvider,
                                 TenantProvisioning.axonServer(configuration, DemoLifecycle.KNOWN_TENANTS),
                                 configuration::shutdown);
    }
}
