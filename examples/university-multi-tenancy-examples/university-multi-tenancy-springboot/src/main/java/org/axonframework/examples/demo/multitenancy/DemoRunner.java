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
import org.axonframework.examples.demo.multitenancy.shared.ProviderAmbiguityGuardrail;
import org.axonframework.examples.demo.multitenancy.shared.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatsStore;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Drives the demo end to end once the Spring Boot application has started, then stops it. It resolves
 * the framework gateways, the Axon configuration, and the tenant-aware providers as ordinary beans and
 * hands them to the shared {@link DemoLifecycle}, so it runs the identical story the declarative demo
 * runs, only against Axon Server.
 * <p>
 * It is excluded from the {@code test} profile, so the smoke test can boot the same autoconfigured
 * context and drive the lifecycle itself rather than have this runner stop the context out from under
 * it.
 */
@Component
@Profile("!test")
public class DemoRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DemoRunner.class);

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final TenantComponentProvider<CourseStatsStore> statsProvider;
    private final TenantComponentProvider<AuditLog> auditProvider;
    private final AxonConfiguration axonConfiguration;
    private final ConfigurableApplicationContext applicationContext;

    /**
     * Constructs the runner from the autoconfigured framework and demo beans.
     *
     * @param commandGateway     the gateway enrolments are sent on
     * @param queryGateway       the gateway statistics are read on
     * @param statsProvider      the provider of the per-tenant course-statistics stores
     * @param auditProvider      the provider of the per-tenant audit logs
     * @param axonConfiguration  the Axon configuration, to resolve the Axon Server tenant provider from
     * @param applicationContext the context to close when the demo has finished, triggering cleanup
     */
    public DemoRunner(CommandGateway commandGateway,
                      QueryGateway queryGateway,
                      TenantComponentProvider<CourseStatsStore> statsProvider,
                      TenantComponentProvider<AuditLog> auditProvider,
                      AxonConfiguration axonConfiguration,
                      ConfigurableApplicationContext applicationContext) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
        this.statsProvider = statsProvider;
        this.auditProvider = auditProvider;
        this.axonConfiguration = axonConfiguration;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) {
        logger.info("Two providers for one component type are rejected at configuration time: {}",
                    ProviderAmbiguityGuardrail.rejectsTwoProvidersForOneType());
        DemoOutcome outcome = DemoLifecycle.run(commandGateway,
                                                queryGateway,
                                                statsProvider,
                                                auditProvider,
                                                TenantProvisioning.axonServer(axonConfiguration,
                                                                              DemoLifecycle.KNOWN_TENANTS),
                                                applicationContext::close);
        logger.info("Demo finished. Outcome: {}", outcome);
    }
}
