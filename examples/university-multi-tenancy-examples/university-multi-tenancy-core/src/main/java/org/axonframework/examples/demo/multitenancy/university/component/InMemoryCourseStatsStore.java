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

package org.axonframework.examples.demo.multitenancy.university.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link CourseStatsStore}, backing the demo without external infrastructure.
 * <p>
 * A real application would back this with the tenant's own datasource. The tenant identifier is
 * carried only so the closing of an instance is visible in the log when its tenant is removed.
 */
public class InMemoryCourseStatsStore implements CourseStatsStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryCourseStatsStore.class);

    private final String tenantId;
    private final ConcurrentMap<String, AtomicInteger> enrolmentsByCourse = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Constructs a store for the tenant with the given {@code tenantId}.
     *
     * @param tenantId the identifier of the tenant this store belongs to
     */
    public InMemoryCourseStatsStore(String tenantId) {
        this.tenantId = Objects.requireNonNull(tenantId, "The tenant id must not be null");
    }

    @Override
    public void recordEnrolment(String courseId) {
        enrolmentsByCourse.computeIfAbsent(courseId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public List<CourseStatistics> statistics() {
        return enrolmentsByCourse.entrySet()
                                 .stream()
                                 .map(entry -> new CourseStatistics(entry.getKey(), entry.getValue().get()))
                                 .toList();
    }

    /**
     * Returns whether this store has been closed, meaning its tenant was removed. This is demo
     * instrumentation for observing the framework-driven cleanup, so it lives on the in-memory
     * implementation rather than on the tenant-aware {@link CourseStatsStore} interface users learn from.
     *
     * @return {@code true} if this store was closed
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        logger.info("Closed course-stats store for tenant [{}].", tenantId);
    }
}
