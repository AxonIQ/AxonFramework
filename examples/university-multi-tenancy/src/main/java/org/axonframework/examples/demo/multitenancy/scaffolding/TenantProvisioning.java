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

package org.axonframework.examples.demo.multitenancy.scaffolding;

import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import io.axoniq.framework.messaging.multitenancy.axonserver.AxonServerTenantProvider;
import org.axonframework.common.configuration.AxonConfiguration;

import java.util.List;

/**
 * How this run provisions and removes its tenants, and the only thing that differs between the demo's
 * two runs. The rest of the lifecycle is identical, so isolating these three operations captures the
 * whole difference between the in-memory and Axon Server runs.
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
     * event. Against Axon Server this also creates the tenant's context.
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
     * tenant set the run walks through. The {@link AxonServerTenantProvider} and connection manager are
     * resolved from the started {@code configuration}.
     *
     * @param configuration the started configuration to resolve the Axon Server components from
     * @param knownTenants  the tenants known before startup, whose contexts are created up front
     * @return the Axon Server tenant provisioning
     */
    static TenantProvisioning axonServer(AxonConfiguration configuration, List<TenantDescriptor> knownTenants) {
        AxonServerTenantProvider tenantProvider =
                (AxonServerTenantProvider) configuration.getComponent(TenantProvider.class);
        AxonServerTenantContexts contexts =
                new AxonServerTenantContexts(configuration.getComponent(AxonServerConnectionManager.class));
        return new TenantProvisioning() {
            @Override
            public void prepareKnownTenants() {
                knownTenants.forEach(tenant -> contexts.createContextIfAbsent(tenant.tenantId()));
            }

            @Override
            public void addTenant(TenantDescriptor tenant) {
                contexts.createContextIfAbsent(tenant.tenantId());
                tenantProvider.addTenant(tenant);
            }

            @Override
            public void removeTenant(TenantDescriptor tenant) {
                tenantProvider.removeTenant(tenant);
                contexts.deleteContext(tenant.tenantId());
            }
        };
    }
}
