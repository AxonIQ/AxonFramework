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

import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.messaging.multitenancy.api.TenantComponentProvider;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.testcontainer.AxonServerContainer;
import org.awaitility.Awaitility;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.demo.multitenancy.shared.AxonServerTenantContexts;
import org.axonframework.examples.demo.multitenancy.shared.DemoLifecycle;
import org.axonframework.examples.demo.multitenancy.shared.DemoOutcome;
import org.axonframework.examples.demo.multitenancy.shared.ProviderAmbiguityGuardrail;
import org.axonframework.examples.demo.multitenancy.shared.TenantProvisioning;
import org.axonframework.examples.demo.multitenancy.university.component.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.component.CourseStatsStore;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test booting the auto-configured Spring Boot application against a real Axon Server, and
 * driving the shared demo lifecycle through the framework beans the multi-tenancy auto-configuration
 * wired. Because the Spring Boot multi-tenancy path activates only against Axon Server, the test runs
 * one in a container. It asserts the same observed outcome as the declarative demo: per-tenant
 * isolation across both component types, the unknown-tenant and ambiguity guardrails, destroy on tenant
 * removal, and cleanup on shutdown. It also asserts that the {@code _admin} context is filtered out of
 * the discovered tenants.
 * <p>
 * The application is booted directly rather than through {@code @SpringBootTest}, because the demo stops
 * the context as its final step and a managed test context must not be closed from within a test. The
 * {@code test} profile keeps the application's own {@link DemoRunner} from running, so this test drives
 * the lifecycle.
 * <p>
 * Tenants are separate Axon Server contexts, and hosting several of them needs an Enterprise Edition
 * license, which is mounted into the container. The license is expected on the test classpath as
 * {@code axon-server.license}: locally it is the file kept next to the demos and copied in by this
 * module's POM; in a repository CI run it is written from a secret before the build (see the examples
 * workflow). When no license is available -- a fork PR, or a clone without the license file -- there is
 * nothing to run the multiple tenant contexts against, so the test skips itself rather than fail. In a
 * repository CI run and locally the license is present, so it always runs there. The test needs Docker.
 */
@Testcontainers
@EnabledIf("licenseAvailable")
class MultiTenancyDemoIT {

    private static final String LICENSE_RESOURCE = "axon-server.license";
    private static final String ADMIN_CONTEXT = "_admin";

    @SuppressWarnings("unused") // Referenced by @EnabledIf to gate the test on a usable license.
    static boolean licenseAvailable() {
        try (InputStream license = MultiTenancyDemoIT.class.getClassLoader().getResourceAsStream(LICENSE_RESOURCE)) {
            return license != null && license.read() != -1;
        } catch (IOException e) {
            return false;
        }
    }

    @Container
    private static final AxonServerContainer AXON_SERVER = new AxonServerContainer()
            .withAxonServerHostname("localhost")
            .withDevMode(true)
            .withDcbContext(true)
            .withLicense(LICENSE_RESOURCE);

    @Test
    void runsTheTenantLifecycleEndToEnd() {
        // given the auto-configured Spring Boot application, booted against the containerized Axon Server
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MultiTenancyApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties("axon.axonserver.servers=" + AXON_SERVER.getAxonServerAddress())
                .run()) {

            AxonConfiguration configuration = context.getBean(AxonConfiguration.class);
            TenantProvider tenantProvider = configuration.getComponent(TenantProvider.class);
            AxonServerTenantContexts serverContexts =
                    new AxonServerTenantContexts(configuration.getComponent(AxonServerConnectionManager.class));

            // then the _admin context exists on the server but is filtered out, so it never becomes a tenant
            Awaitility.await("initial tenant discovery")
                      .atMost(Duration.ofSeconds(15))
                      .until(() -> !tenantProvider.tenants().isEmpty());
            assertThat(serverContexts.allContexts()).contains(ADMIN_CONTEXT);
            assertThat(tenantProvider.tenants()).extracting(TenantDescriptor::tenantId).doesNotContain(ADMIN_CONTEXT);

            // when the demo runs end to end through the beans the multi-tenancy auto-configuration wired
            DemoOutcome outcome = DemoLifecycle.run(context.getBean(CommandGateway.class),
                                                    context.getBean(QueryGateway.class),
                                                    courseStatsProvider(context),
                                                    auditProvider(context),
                                                    TenantProvisioning.axonServer(configuration,
                                                                                  DemoLifecycle.KNOWN_TENANTS),
                                                    context::close);

            // then both of Springfield's components saw only its own two enrolments, matched by type
            // and isolated from the other tenants
            assertThat(outcome.springfieldEnrolments()).isEqualTo(2);
            assertThat(outcome.springfieldAuditEntries()).isEqualTo(2);
            // and the tenant added at runtime recorded its own enrolment in isolation
            assertThat(outcome.ogdenvilleEnrolments()).isEqualTo(1);
            // and a command for an unknown tenant was rejected
            assertThat(outcome.unknownTenantRejected()).isTrue();
            // and removing Shelbyville closed its instances
            assertThat(outcome.shelbyvilleClosedOnRemoval()).isTrue();
            // and shutting down closed every remaining tenant's instances
            assertThat(outcome.allClosedOnShutdown()).isTrue();

            // and registering two providers for one component type is rejected at configuration time
            assertThat(ProviderAmbiguityGuardrail.rejectsTwoProvidersForOneType()).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private static TenantComponentProvider<CourseStatsStore> courseStatsProvider(
            ConfigurableApplicationContext context) {
        return context.getBean("courseStatsProvider", TenantComponentProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static TenantComponentProvider<AuditLog> auditProvider(ConfigurableApplicationContext context) {
        return context.getBean("auditLogProvider", TenantComponentProvider.class);
    }
}
