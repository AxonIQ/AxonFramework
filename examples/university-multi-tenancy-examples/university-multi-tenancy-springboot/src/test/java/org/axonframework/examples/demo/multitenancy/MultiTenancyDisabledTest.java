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

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that multi-tenancy can be switched off entirely through {@code axon.multitenancy.enabled=false}.
 * <p>
 * With the feature disabled, the auto-configuration installs nothing: no tenant parameter resolver. The
 * enrolment command handler still declares a tenant-scoped {@code CourseStatsStore} parameter, so
 * with no resolver for it the application fails to start, its handler inspection unable to resolve that
 * parameter. That failure is the observable proof that the disable toggle truly takes the whole
 * tenant-aware machinery out, rather than leaving it partially wired.
 * <p>
 * This needs no Axon Server, so it runs as an ordinary unit test.
 */
class MultiTenancyDisabledTest {

    @Test
    void disablingMultiTenancyRemovesTenantScopedParameterResolution() {
        // when the application is started with multi-tenancy disabled
        // then it fails to start, because the tenant-scoped handler parameter can no longer be resolved
        assertThatThrownBy(() -> new SpringApplicationBuilder(MultiTenancyApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties("axon.axonserver.enabled=false", "axon.multitenancy.enabled=false")
                .run()
                .close())
                .hasStackTraceContaining("CourseStatsStore");
    }
}
