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

package org.axonframework.examples.demo.multitenancy.university.read.coursestats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link CourseStatsRepository}, backing the demo without external infrastructure.
 * <p>
 * A real application would back this with the tenant's own datasource. The tenant identifier is
 * carried only so the closing of an instance is visible in the log when its tenant is removed.
 */
public class InMemoryCourseStatsRepository implements CourseStatsRepository {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryCourseStatsRepository.class);

    private final String tenantId;
    private final ConcurrentMap<String, AtomicInteger> enrolmentsByCourse = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Constructs a repository for the tenant with the given {@code tenantId}.
     *
     * @param tenantId the identifier of the tenant this repository belongs to
     */
    public InMemoryCourseStatsRepository(String tenantId) {
        this.tenantId = tenantId;
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

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        logger.info("Closed course-stats repository for tenant [{}].", tenantId);
    }
}
