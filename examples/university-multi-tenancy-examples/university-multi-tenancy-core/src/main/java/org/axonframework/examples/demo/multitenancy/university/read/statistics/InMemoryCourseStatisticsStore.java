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

package org.axonframework.examples.demo.multitenancy.university.read.statistics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link CourseStatisticsStore}, backing the demo without external infrastructure.
 * <p>
 * A real application would back this with the tenant's own datasource. The tenant identifier is
 * carried only so the closing of an instance is visible in the log when its tenant is removed.
 */
class InMemoryCourseStatisticsStore implements CourseStatisticsStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryCourseStatisticsStore.class);

    private final String tenantId;
    // The enrolled students per course rather than a count, so recording the same enrollment twice is a no-op.
    private final ConcurrentMap<String, Set<String>> enrolledStudentsByCourse = new ConcurrentHashMap<>();
    // The capacity per course, so a course with no seats left can be told apart from one still filling up.
    private final ConcurrentMap<String, Integer> capacityByCourse = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Constructs a store for the tenant with the given {@code tenantId}.
     *
     * @param tenantId the identifier of the tenant this store belongs to
     */
    InMemoryCourseStatisticsStore(String tenantId) {
        this.tenantId = Objects.requireNonNull(tenantId, "The tenant id must not be null");
    }

    @Override
    public void recordCourseCapacity(String courseId, int capacity) {
        capacityByCourse.put(courseId, capacity);
    }

    @Override
    public boolean isEveryCourseFull() {
        if (enrolledStudentsByCourse.isEmpty()) {
            return false;
        }
        return enrolledStudentsByCourse.keySet().stream().allMatch(this::isCourseFull);
    }

    private boolean isCourseFull(String courseId) {
        Integer capacity = capacityByCourse.get(courseId);
        if (capacity == null) {
            return false;
        }
        return enrolledStudentsByCourse.getOrDefault(courseId, Set.of()).size() >= capacity;
    }

    @Override
    public boolean recordEnrollment(String courseId, String studentId) {
        return enrolledStudentsByCourse.computeIfAbsent(courseId, ignored -> ConcurrentHashMap.newKeySet())
                                       .add(studentId);
    }

    @Override
    public List<CourseStatistics> statistics() {
        return enrolledStudentsByCourse.entrySet()
                                       .stream()
                                       .map(entry -> new CourseStatistics(entry.getKey(), entry.getValue().size()))
                                       .toList();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        logger.info("Closed course-statistics store for tenant [{}].", tenantId);
    }
}
