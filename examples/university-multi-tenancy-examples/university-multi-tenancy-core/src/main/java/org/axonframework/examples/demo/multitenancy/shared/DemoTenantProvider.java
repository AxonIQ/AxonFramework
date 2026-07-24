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

import io.axoniq.framework.messaging.multitenancy.api.MultiTenantAwareComponent;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider;
import org.axonframework.common.Registration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * In-memory {@link TenantProvider} for the demos, standing in for the Axon Server backed provider that
 * discovers one context per tenant. Both demos use it to run without external infrastructure.
 * <p>
 * It mirrors the subscribe-and-replay semantics of the real provider: tenants known at subscription
 * time are replayed to a newly subscribed component, and tenants added or removed afterward reach
 * every subscribed component through its registration hooks. Cancelling a subscription cancels every
 * tenant registration made on the component's behalf, releasing that component's per-tenant resources.
 */
public class DemoTenantProvider implements TenantProvider {

    // Guarded by this instance's monitor: every method touching these lists is synchronized.
    private final List<TenantDescriptor> tenantDescriptors = new ArrayList<>();
    private final List<MultiTenantAwareComponent> subscribedComponents = new ArrayList<>();
    // One entry per tenant-component pair, so cancellation can select by either dimension.
    private final List<TenantRegistration> registrations = new ArrayList<>();

    /**
     * Constructs a provider knowing the given {@code initialTenants} from the start.
     *
     * @param initialTenants the tenants known before any component subscribes
     */
    public DemoTenantProvider(TenantDescriptor... initialTenants) {
        this.tenantDescriptors.addAll(List.of(initialTenants));
    }

    @Override
    public synchronized Registration subscribe(MultiTenantAwareComponent component) {
        subscribedComponents.add(component);
        tenantDescriptors.forEach(tenant -> registrations.add(new TenantRegistration(
                tenant, component, component.registerTenant(tenant)
        )));
        return () -> cancelSubscription(component);
    }

    private synchronized boolean cancelSubscription(MultiTenantAwareComponent component) {
        cancelRegistrationsMatching(registration -> registration.component() == component);
        subscribedComponents.remove(component);
        return true;
    }

    @Override
    public synchronized List<TenantDescriptor> tenants() {
        return List.copyOf(tenantDescriptors);
    }

    /**
     * Adds a tenant at runtime, registering and starting it on every subscribed component.
     *
     * @param tenant the tenant to add
     */
    public synchronized void addTenant(TenantDescriptor tenant) {
        if (tenantDescriptors.contains(tenant)) {
            return;
        }
        tenantDescriptors.add(tenant);
        subscribedComponents.forEach(component -> registrations.add(new TenantRegistration(
                tenant, component, component.registerAndStartTenant(tenant)
        )));
    }

    /**
     * Removes a tenant, cancelling its registration on every subscribed component so their
     * per-tenant instances are destroyed.
     *
     * @param tenant the tenant to remove
     */
    public synchronized void removeTenant(TenantDescriptor tenant) {
        if (!tenantDescriptors.contains(tenant)) {
            return;
        }
        // Cancel the tenant's registrations before dropping it. If a cancel throws, the tenant stays
        // recorded so the removal can be retried rather than leaving its instances uncancelled.
        cancelRegistrationsMatching(registration -> registration.tenant().equals(tenant));
        tenantDescriptors.remove(tenant);
    }

    private synchronized void cancelRegistrationsMatching(Predicate<TenantRegistration> criterion) {
        List<TenantRegistration> matching = registrations.stream().filter(criterion).toList();
        // Cancel first, then drop the entries. If a cancel throws, the registrations stay recorded so
        // the cancellation can be retried rather than being lost. A retry re-cancels the ones that
        // already succeeded, which is harmless as Registration#cancel is idempotent.
        matching.reversed().forEach(registration -> registration.registration().cancel());
        registrations.removeAll(matching);
    }

    private record TenantRegistration(TenantDescriptor tenant,
                                      MultiTenantAwareComponent component,
                                      Registration registration) {

    }
}
