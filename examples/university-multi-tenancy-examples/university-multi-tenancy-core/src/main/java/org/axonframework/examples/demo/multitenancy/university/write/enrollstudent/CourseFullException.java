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

package org.axonframework.examples.demo.multitenancy.university.write.enrollstudent;

/**
 * Raised when a student is enrolled in a course that has no free seats left. The decision is made from
 * the course sourced from its tenant's own event store, so a course full in one tenant says nothing
 * about the same course identifier in another tenant.
 */
public class CourseFullException extends RuntimeException {

    /**
     * Constructs the exception for the given full {@code courseId} and its {@code capacity}.
     *
     * @param courseId the identifier of the full course
     * @param capacity the number of seats the course offers, all taken
     */
    public CourseFullException(String courseId, int capacity) {
        super("Course [" + courseId + "] is full: all " + capacity + " seats are taken");
    }
}
