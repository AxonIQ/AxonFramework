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

package org.axonframework.examples.demo.multitenancy.shared.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory {@link AuditLog}, backing the demo without external infrastructure.
 */
class InMemoryAuditLog implements AuditLog {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryAuditLog.class);

    private final String tenantId;
    // A set, so recording the same entry twice is a no-op. Insertion-ordered and lock-free for reads, so the
    // log still reads in the order things happened.
    private final Set<String> entries = new CopyOnWriteArraySet<>();
    private volatile boolean closed = false;

    /**
     * Constructs an audit log for the tenant with the given {@code tenantId}.
     *
     * @param tenantId the identifier of the tenant this audit log belongs to
     */
    InMemoryAuditLog(String tenantId) {
        this.tenantId = Objects.requireNonNull(tenantId, "The tenant id must not be null");
    }

    @Override
    public void record(String entry) {
        entries.add(entry);
    }

    @Override
    public List<String> entries() {
        return List.copyOf(entries);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        logger.info("Closed audit log for tenant [{}].", tenantId);
    }
}
