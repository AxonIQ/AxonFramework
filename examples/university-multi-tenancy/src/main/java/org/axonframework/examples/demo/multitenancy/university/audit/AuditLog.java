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

package org.axonframework.examples.demo.multitenancy.university.audit;

import java.util.List;

/**
 * A tenant-scoped audit trail, the demo's second tenant-aware component type.
 * <p>
 * It exists alongside the course-statistics repository to show that several tenant-scoped types
 * can be registered, each matched to a handler parameter by its own type. One instance exists per
 * tenant, and being {@link AutoCloseable} it is closed when its tenant is removed.
 */
public interface AuditLog extends AutoCloseable {

    /**
     * Records the given audit {@code entry} for this tenant.
     *
     * @param entry the audit entry to record
     */
    void record(String entry);

    /**
     * Returns the audit entries recorded for this tenant.
     *
     * @return the audit entries recorded for this tenant
     */
    List<String> entries();

    /**
     * Returns whether this audit log has been closed, meaning its tenant was removed.
     * <p>
     * This is demo instrumentation for observing the framework-driven cleanup. A real tenant-aware
     * component only needs its domain methods plus {@link AutoCloseable}; it would not expose this.
     *
     * @return {@code true} if this audit log was closed
     */
    boolean isClosed();

    @Override
    void close();
}
