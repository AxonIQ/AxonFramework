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
import org.axonframework.examples.demo.multitenancy.shared.run.DemoApplication;
import org.axonframework.examples.demo.multitenancy.shared.tenant.AxonServerTenantContextManager;
import org.axonframework.examples.demo.multitenancy.shared.run.DemoLifecycle;
import org.axonframework.examples.demo.multitenancy.shared.run.DemoOutcome;
import org.axonframework.examples.demo.multitenancy.shared.run.EventStorageOutcome;
import org.axonframework.examples.demo.multitenancy.shared.run.ProviderAmbiguityGuardrail;
import org.axonframework.examples.demo.multitenancy.shared.run.SnapshottingOutcome;
import org.axonframework.examples.demo.multitenancy.shared.run.StreamingOutcome;
import org.axonframework.examples.demo.multitenancy.shared.audit.AuditLog;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.CourseStatisticsStore;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.StatisticsConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test booting the autoconfigured Spring Boot application against a real Axon Server, and
 * driving the shared demo lifecycle through the framework beans the multi-tenancy autoconfiguration
 * wired. Because the Spring Boot multi-tenancy path activates only against Axon Server, the test runs
 * one in a container. It asserts the same observed outcome as the declarative demo: per-tenant
 * isolation across both component types, subscription-query isolation, the unknown-tenant guardrail on
 * both commands and queries, the ambiguity guardrail, destroy on tenant removal, and cleanup on
 * shutdown. It also asserts that the {@code _admin} context is filtered out of the discovered tenants.
 * <p>
 * Being the Axon Server path, this is also where the per-tenant features the in-memory demo cannot show are
 * asserted: event-store and snapshot isolation, tenant-aware event processing, where one ordinary pooled
 * streaming processor projects every tenant's events into that tenant's own read model, and direct queries
 * actually routed through the per-tenant query connector rather than served from the local segment.
 * <p>
 * The application is booted directly rather than through {@code @SpringBootTest}, because the demo stops
 * the context as its final step and a managed test context must not be closed from within a test. The
 * {@code test} profile keeps the application's own {@link DemoRunner} from running, so this test drives
 * the lifecycle.
 * <p>
 * The test runs a licensed Enterprise Edition Axon Server in a container, so it needs Docker. The README
 * explains how the container is licensed, with a license file or an Axoniq Platform token. The test skips
 * itself when no license source is available.
 */
@Testcontainers
@EnabledIf("axonServerLicensable")
class MultiTenancyDemoIT {

    private static final String LICENSE_RESOURCE = "axon-server.license";
    private static final String TOKEN_ENV_FILE = ".env";
    private static final String PLATFORM_TOKEN_ENV = "AXONIQ_PLATFORM_AUTHENTICATION";
    // A test-specific node name, so the token path does not clash with another Axon Server registered
    // under the default name in the developer's Axoniq Platform workspace (a clash blocks licensing).
    private static final String NODE_NAME = "university-multitenancy-it";
    private static final String ADMIN_CONTEXT = "_admin";

    @SuppressWarnings("unused") // Referenced by @EnabledIf to gate the test on a usable license source.
    static boolean axonServerLicensable() {
        return licenseAvailable() || platformToken() != null;
    }

    private static boolean licenseAvailable() {
        try (InputStream license = MultiTenancyDemoIT.class.getClassLoader().getResourceAsStream(LICENSE_RESOURCE)) {
            return license != null && license.read() != -1;
        } catch (IOException e) {
            return false;
        }
    }

    // Resolves the Axoniq Platform token from the AXONIQ_PLATFORM_AUTHENTICATION environment variable, or,
    // when that is not set, from an AXONIQ_PLATFORM_AUTHENTICATION entry in the .env file next to the demos.
    // That is the same file docker-compose reads, so the token is configured in one place for both, with no
    // shell or IDE setup. CI sets a real environment variable, which takes precedence.
    private static String platformToken() {
        String fromEnvironment = System.getenv(PLATFORM_TOKEN_ENV);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return tokenFromDotEnvFile();
    }

    private static String tokenFromDotEnvFile() {
        try (InputStream dotEnv = MultiTenancyDemoIT.class.getClassLoader().getResourceAsStream(TOKEN_ENV_FILE)) {
            if (dotEnv == null) {
                return null;
            }
            String prefix = PLATFORM_TOKEN_ENV + "=";
            return new String(dotEnv.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()).strip())
                    .map(MultiTenancyDemoIT::stripSurroundingQuotes)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // A .env value may be wrapped in a pair of double or single quotes. Those quotes are not part of the
    // token, so one surrounding pair is removed before the value is used.
    private static String stripSurroundingQuotes(String value) {
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    @Container
    private static final AxonServerContainer AXON_SERVER = licensedContainer();

    // The license file wins over the token, so a repository CI run (which writes the license from a
    // secret) always boots from the license file. The token path is the local fallback for developers
    // who have an Axoniq Platform token but no license file.
    private static AxonServerContainer licensedContainer() {
        AxonServerContainer container = new AxonServerContainer()
                .withAxonServerHostname("localhost")
                .withDevMode(true)
                .withDcbContext(true);
        if (licenseAvailable()) {
            return container.withLicense(LICENSE_RESOURCE);
        }
        String token = platformToken();
        if (token != null) {
            return container.withAxonServerName(NODE_NAME)
                            .withEnv(PLATFORM_TOKEN_ENV, token);
        }
        // Neither source is present, so the @EnabledIf gate skips the test. The field still needs a value.
        return container;
    }

    @Test
    void runsTheTenantLifecycleEndToEnd() {
        // given the autoconfigured Spring Boot application, booted against the containerized Axon Server
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MultiTenancyApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties("axon.axonserver.servers=" + AXON_SERVER.getAxonServerAddress())
                .run()) {

            AxonConfiguration configuration = context.getBean(AxonConfiguration.class);
            TenantProvider tenantProvider = configuration.getComponent(TenantProvider.class);
            AxonServerTenantContextManager serverContexts =
                    new AxonServerTenantContextManager(configuration.getComponent(AxonServerConnectionManager.class));

            // then the _admin context exists on the server but is filtered out, so it never becomes a tenant
            Awaitility.await("initial tenant discovery")
                      .atMost(Duration.ofSeconds(15))
                      .until(() -> !tenantProvider.tenants().isEmpty());
            assertThat(serverContexts.allContexts()).contains(ADMIN_CONTEXT);
            assertThat(tenantProvider.tenants()).extracting(TenantDescriptor::tenantId).doesNotContain(ADMIN_CONTEXT);

            // when the demo runs end to end through the beans, the multi-tenancy autoconfiguration wired
            DemoOutcome outcome = DemoLifecycle.run(DemoApplication.axonServer(configuration,
                                                                               courseStatisticsProvider(context),
                                                                               auditProvider(context),
                                                                               context::close));

            // then both of Springfield's components saw only its own two enrollments, matched by type
            // and isolated from the other tenants
            assertThat(outcome.springfieldEnrollments()).isEqualTo(2);
            assertThat(outcome.springfieldAuditEntries()).isEqualTo(2);
            // and the tenant added at runtime recorded its own enrollment in isolation
            assertThat(outcome.ogdenvilleEnrollments()).isEqualTo(1);
            // and a command for an unknown tenant was rejected
            assertThat(outcome.unknownTenantRejected()).isTrue();
            // and a query for an unknown tenant was rejected too
            assertThat(outcome.queryRejections().rejectedForUnknownTenant()).isTrue();
            // and so was a query naming no tenant at all, which has nothing to resolve components from
            assertThat(outcome.queryRejections().rejectedForMissingTenant()).isTrue();
            // and Shelbyville stopped being queryable once its tenant was removed, which is the read-side
            // counterpart of the unknown-tenant rejection
            assertThat(outcome.queryRejections().rejectedForRemovedTenant()).isTrue();
            // and Springfield's and Shelbyville's own subscription queries each received only their own
            // updates, routed through their own tenant's Axon Server connection
            assertThat(outcome.subscriptionQuery().isolatedByTenant()).isTrue();
            // each seeing its own initial result plus one update per enrollment, and nothing more
            assertThat(outcome.subscriptionQuery().springfieldUpdatesReceived())
                    .isEqualTo(outcome.springfieldEnrollments() + 1);
            assertThat(outcome.subscriptionQuery().shelbyvilleUpdatesReceived())
                    .isEqualTo(outcome.springfieldEnrollments() + 1);
            // and filling Springfield's course completed only its own subscription, leaving Shelbyville's
            // open on its course's free seat, so completion is scoped to a tenant just as emission is
            assertThat(outcome.subscriptionQuery().completionScopedToTenant()).isTrue();
            // and removing Shelbyville closed its instances
            assertThat(outcome.shelbyvilleClosedOnRemoval()).isTrue();
            // and shutting down closed every remaining tenant's instances
            assertThat(outcome.allClosedOnShutdown()).isTrue();

            // and per-tenant event storage kept the same course identifier isolated across tenants:
            // Springfield's course filled up and rejected a further enrollment as full, while the same
            // course identifier still accepted one in Shelbyville, sourced from its own event store
            EventStorageOutcome eventStorage = outcome.eventStorage();
            assertThat(eventStorage.demonstrated()).isTrue();
            assertThat(eventStorage.springfieldRejectedWhenFull()).isTrue();
            assertThat(eventStorage.shelbyvilleAcceptedSameCourseId()).isTrue();

            // and per-tenant snapshotting kept the same course identifier isolated too: both tenants' own
            // stores hold a snapshot of that identifier, and each holds only its own tenant's student, so
            // neither tenant read the other's snapshot
            SnapshottingOutcome snapshotting = outcome.snapshotting();
            assertThat(snapshotting.demonstrated()).isTrue();
            assertThat(snapshotting.bothTenantsHoldOwnSnapshot()).isTrue();
            assertThat(snapshotting.snapshotsHoldTheirOwnStudents()).isTrue();

            // and one ordinary pooled streaming processor projected every tenant's events, rather than one
            // processor per tenant. Asserted by name, so a per-tenant processor would show up as an extra entry
            StreamingOutcome streaming = outcome.streaming();
            assertThat(streaming.demonstrated()).isTrue();
            assertThat(streaming.processorNames()).containsExactly(StatisticsConfiguration.PROCESSOR_NAME);
            // and that one processor kept the three tenants' read models apart. Springfield and Shelbyville
            // hold the same course identifier, so a leak between them would show up as a wrong count here
            assertThat(streaming.springfieldProjected()).isEqualTo(2);
            assertThat(streaming.shelbyvilleProjected()).isEqualTo(2);
            // and the tenant added at runtime was picked up: the processor re-opened its stream to include a
            // tenant that did not exist when it started, and projected that tenant's enrollment
            assertThat(streaming.ogdenvilleProjected()).isEqualTo(1);

            // and registering two providers for one component type is rejected at configuration time
            assertThat(ProviderAmbiguityGuardrail.rejectsTwoProvidersForOneType()).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private static TenantComponentProvider<CourseStatisticsStore> courseStatisticsProvider(
            ConfigurableApplicationContext context) {
        return context.getBean("courseStatisticsProvider", TenantComponentProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static TenantComponentProvider<AuditLog> auditProvider(ConfigurableApplicationContext context) {
        return context.getBean("auditLogProvider", TenantComponentProvider.class);
    }
}
