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

package org.axonframework.integrationtests.testsuite.infrastructure;

import org.axonframework.common.configuration.ComponentRegistry;

import java.util.List;

/**
 * {@link TestInfrastructure} implementation that uses the framework's default in-memory components.
 * <p>
 * No containers are started. The only configuration step is disabling the AxonServer-backed enhancers so that
 * classpath scanning does not replace the in-memory defaults ({@code SimpleCommandBus}, {@code SimpleQueryBus},
 * {@code InMemoryEventStorageEngine}) with distributed AxonServer equivalents, and does not activate multi-tenancy,
 * which is AxonServer-backed as well.
 * <p>
 * Data "purging" is a no-op: each test shuts down and recreates the
 * {@link org.axonframework.common.configuration.AxonConfiguration} via
 * {@link org.axonframework.integrationtests.testsuite.AbstractIT#startApp()}, so the in-memory stores
 * are always fresh at the start of every test.
 * <p>
 * Leaf test classes should hold a {@code private static final} instance of this class:
 * <pre>{@code
 * private static final TestInfrastructure INFRASTRUCTURE = new InMemoryTestInfrastructure();
 *
 * @Override
 * protected TestInfrastructure testInfrastructure() {
 *     return INFRASTRUCTURE;
 * }
 * }</pre>
 *
 * @since 5.1.0
 */
public final class InMemoryTestInfrastructure implements TestInfrastructure {

    /**
     * FQCN string used to disable the AxonServer configuration enhancer without introducing a compile-time dependency
     * on the {@code axon-server-connector} module. The {@link ComponentRegistry#disableEnhancer(String)} overload
     * treats "enhancer not found on classpath" and "enhancer found and disabled" identically, so this is safe even in
     * environments where the connector JAR is absent.
     * <p>
     */
    private static final String AXON_SERVER_ENHANCER_FQCN =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    /**
     * FQCN strings of the multi-tenancy configuration enhancers, disabled for the same reason and in the same way as
     * {@link #AXON_SERVER_ENHANCER_FQCN}. Tenants are AxonServer contexts, so multi-tenancy cannot function on an
     * in-memory infrastructure: its tenant provider would try to resolve an AxonServer connection manager at startup.
     */
    private static final List<String> MULTI_TENANCY_ENHANCER_FQCNS = List.of(
            "io.axoniq.framework.messaging.multitenancy.configuration.MultiTenancyConfigurationDefaults",
            "io.axoniq.framework.messaging.multitenancy.axonserver.configuration.AxonServerMultiTenancyConfigurationDefaults",
            "io.axoniq.framework.messaging.multitenancy.annotation.TenantComponentParameterResolverFactoryConfigurationEnhancer"
    );

    @Override
    public void start() {
        // No containers to start.
    }

    @Override
    public void configureInfrastructure(ComponentRegistry registry) {
        // Prevent the AxonServer enhancer from replacing the in-memory defaults.
        // EventSourcingConfigurationDefaults already provides InMemoryEventStorageEngine;
        // MessagingConfigurationDefaults provides SimpleCommandBus / SimpleQueryBus.
        registry.disableEnhancer(AXON_SERVER_ENHANCER_FQCN);
        // Multi-tenancy is AxonServer-backed, so it has no place on an in-memory infrastructure either.
        MULTI_TENANCY_ENHANCER_FQCNS.forEach(registry::disableEnhancer);
    }

    @Override
    public void purgeData() {
        // No-op: the configuration is recreated from scratch in every startApp() call,
        // so in-memory stores are always empty at the beginning of each test.
    }

    @Override
    public void stop() {
        // Nothing to release — no containers, no pools, no external resources.
    }
}
