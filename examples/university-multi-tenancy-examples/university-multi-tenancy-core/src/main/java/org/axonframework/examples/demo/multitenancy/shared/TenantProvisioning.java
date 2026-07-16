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

package org.axonframework.examples.demo.multitenancy.shared;

import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import org.awaitility.Awaitility;
import org.axonframework.common.configuration.AxonConfiguration;

import java.time.Duration;
import java.util.List;

/**
 * How a run provisions and removes its tenants, and the only thing that differs between the demo's
 * in-memory and Axon Server runs. The rest of the lifecycle is identical, so isolating these three
 * operations captures the whole difference between the two backings.
 * <p>
 * Get one through {@link #inMemory(DemoTenantProvider)} or {@link #axonServer(AxonConfiguration, List)}.
 */
public interface TenantProvisioning {

    /**
     * Ensures the tenants known before startup exist. In memory they already do, so this is a no-op.
     * Against Axon Server it creates their contexts.
     */
    void prepareKnownTenants();

    /**
     * Registers a tenant discovered at runtime, so its per-tenant instances materialize on its first
     * message. Against Axon Server this also creates the tenant's context.
     *
     * @param tenant the tenant to add
     */
    void addTenant(TenantDescriptor tenant);

    /**
     * Deregisters a tenant, releasing its per-tenant instances. Against Axon Server this also deletes
     * the tenant's context.
     *
     * @param tenant the tenant to remove
     */
    void removeTenant(TenantDescriptor tenant);

    /**
     * Provisioning over an in-memory {@link DemoTenantProvider}: tenants are just entries in the
     * provider, with no Axon Server contexts to manage.
     *
     * @param tenantProvider the in-memory provider supplying the tenants
     * @return the in-memory tenant provisioning
     */
    static TenantProvisioning inMemory(DemoTenantProvider tenantProvider) {
        return new TenantProvisioning() {
            @Override
            public void prepareKnownTenants() {
                // The predefined tenants are already known to the in-memory provider.
            }

            @Override
            public void addTenant(TenantDescriptor tenant) {
                tenantProvider.addTenant(tenant);
            }

            @Override
            public void removeTenant(TenantDescriptor tenant) {
                tenantProvider.removeTenant(tenant);
            }
        };
    }

    /**
     * Provisioning over Axon Server: each tenant is a real context. It creates the contexts of the
     * tenants it registers and deletes the context of the tenant it removes, so the server reflects the
     * tenant set the run walks through.
     * <p>
     * The tenants themselves are discovered, not declared: the default auto-discovering
     * {@code AxonServerTenantProvider} resolved from the started {@code configuration} watches Axon
     * Server's contexts and registers each as a tenant, applying its connect predicate. That predicate
     * filters out the {@code _admin} context, so it never becomes a tenant. Because discovery is
     * asynchronous, each operation here waits until the provider reflects it before returning, keeping
     * the run deterministic.
     *
     * @param configuration the started configuration to resolve the Axon Server components from
     * @param knownTenants  the tenants known before startup, whose contexts are created up front
     * @return the Axon Server tenant provisioning
     */
    static TenantProvisioning axonServer(AxonConfiguration configuration, List<TenantDescriptor> knownTenants) {
        TenantProvider tenantProvider = configuration.getComponent(TenantProvider.class);
        AxonServerTenantContexts contexts =
                new AxonServerTenantContexts(configuration.getComponent(AxonServerConnectionManager.class));
        return new TenantProvisioning() {
            @Override
            public void prepareKnownTenants() {
                knownTenants.forEach(tenant -> {
                    contexts.createContextIfAbsent(tenant.tenantId());
                    awaitTenant(tenantProvider, tenant, true);
                });
                // Show that _admin exists as a context on the server but is filtered out, so it is not a tenant.
                contexts.logDiscoveredTenants(tenantProvider.tenants());
            }

            @Override
            public void addTenant(TenantDescriptor tenant) {
                contexts.createContextIfAbsent(tenant.tenantId());
                awaitTenant(tenantProvider, tenant, true);
            }

            @Override
            public void removeTenant(TenantDescriptor tenant) {
                contexts.deleteContext(tenant.tenantId());
                awaitTenant(tenantProvider, tenant, false);
            }
        };
    }

    /**
     * Waits until the given {@code tenantProvider} has discovered ({@code present == true}) or dropped
     * ({@code present == false}) the given {@code tenant}, since Axon Server context discovery is
     * asynchronous.
     *
     * @param tenantProvider the provider whose discovered tenants to watch
     * @param tenant         the tenant to wait for
     * @param present        {@code true} to wait until the tenant is known, {@code false} until it is gone
     */
    private static void awaitTenant(TenantProvider tenantProvider, TenantDescriptor tenant, boolean present) {
        Awaitility.await("tenant [" + tenant.tenantId() + "] " + (present ? "discovered" : "removed"))
                  .atMost(Duration.ofSeconds(30))
                  .until(() -> tenantProvider.tenants().contains(tenant) == present);
    }
}
