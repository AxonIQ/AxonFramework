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

import org.axonframework.examples.demo.multitenancy.shared.DemoLifecycle;
import org.axonframework.examples.demo.multitenancy.shared.Enrollments;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that multi-tenancy can be switched off entirely through {@code axon.multitenancy.enabled=false}.
 * <p>
 * With the feature disabled, dispatching a tenant-scoped enrollment fails because its tenant is never
 * resolved. That failure is the observable proof that the disable toggle takes the whole tenant-aware
 * machinery out, rather than leaving it partially wired.
 * <p>
 * Axon Server is disabled as well, so the test needs none and runs as an ordinary unit test.
 */
class MultiTenancyDisabledTest {

    @Test
    void disablingMultiTenancyRemovesTenantScopedParameterResolution() {
        // Passed as command-line arguments, so they outrank the module's application.yml, which enables
        // Axon Server for the runnable demo.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MultiTenancyApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--axon.axonserver.enabled=false", "--axon.multitenancy.enabled=false")) {

            CommandGateway commandGateway = context.getBean(CommandGateway.class);

            // when a tenant-scoped enrollment is dispatched with multi-tenancy disabled
            // then it fails, because the tenant is never resolved without the multi-tenancy machinery
            assertThatThrownBy(() -> Enrollments.enroll(commandGateway, DemoLifecycle.SPRINGFIELD, "cs-101", "alice"))
                    .matches(Enrollments::causedByTenantNotResolved,
                             "caused by TenantNotResolvedException when multi-tenancy is disabled");
        }
    }
}
