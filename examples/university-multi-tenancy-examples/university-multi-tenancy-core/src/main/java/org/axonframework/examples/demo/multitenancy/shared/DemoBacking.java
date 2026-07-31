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

/**
 * What backs a demo run. The demo runs the same lifecycle two ways, and every difference between them follows
 * from this one choice.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public enum DemoBacking {

    /** One shared in-memory event store for every tenant. Needs no infrastructure. */
    IN_MEMORY,

    /**
     * Axon Server, where each tenant is a context and therefore has its own event store and snapshot store.
     * Needs a running multi-context (Enterprise Edition) server.
     */
    AXON_SERVER;

    /**
     * Whether each tenant has its own event store, which only Axon Server gives.
     *
     * @return {@code true} against Axon Server, {@code false} in memory
     */
    public boolean hasPerTenantEventStore() {
        return this == AXON_SERVER;
    }

    /**
     * Whether a projection builds the read model, rather than the command handler filling it. A projection can
     * only tell which tenant a streamed event belongs to when each tenant has its own event store.
     *
     * @return {@code true} when a projection fills the read model
     */
    public boolean projectsReadModel() {
        return hasPerTenantEventStore();
    }
}
