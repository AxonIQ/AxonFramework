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

import org.axonframework.examples.demo.multitenancy.shared.run.DemoOutcome;
import org.axonframework.examples.demo.multitenancy.shared.run.ProviderAmbiguityGuardrail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test running the demo through its own entry point and asserting the observed outcome:
 * per-tenant isolation, the unknown-tenant guardrail, destroy on tenant removal, and cleanup on
 * shutdown. The configuration-time ambiguity guardrail is asserted separately.
 */
class MultiTenancyDemoTest {

    @Test
    void runsTheTenantLifecycleEndToEnd() {
        // given the in-memory demo application
        MultiTenancyApplication demo = new MultiTenancyApplication();

        // when the demo runs end to end
        DemoOutcome outcome = demo.run();

        // then both of Springfield's components saw only its own two enrollments, matched by type
        // and isolated from the other tenants
        assertThat(outcome.springfieldEnrollments()).isEqualTo(2);
        assertThat(outcome.springfieldAuditEntries()).isEqualTo(2);
        // and the tenant added at runtime recorded its own enrollment in isolation
        assertThat(outcome.ogdenvilleEnrollments()).isEqualTo(1);
        // and a command for an unknown tenant was rejected
        assertThat(outcome.unknownTenantRejected()).isTrue();
        // and removing Shelbyville closed its instances
        assertThat(outcome.shelbyvilleClosedOnRemoval()).isTrue();
        // and shutting down closed every remaining tenant's instances
        assertThat(outcome.allClosedOnShutdown()).isTrue();
        // and the per-tenant event-storage demonstration did not run, as it needs each tenant's own Axon Server
        // event store, which the in-memory demo does not have
        assertThat(outcome.eventStorage().demonstrated()).isFalse();
    }

    @Test
    void rejectsTwoProvidersForOneComponentType() {
        // when two providers are registered for one component type, the framework rejects it at
        // configuration time, since it cannot know which instance a parameter of that type should receive
        assertThat(ProviderAmbiguityGuardrail.rejectsTwoProvidersForOneType()).isTrue();
    }
}
