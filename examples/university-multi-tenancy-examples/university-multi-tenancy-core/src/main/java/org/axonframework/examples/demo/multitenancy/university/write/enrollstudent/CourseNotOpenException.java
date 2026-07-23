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
 * Raised when a student is enrolled in a course that was never opened. The decision is made from the
 * course sourced from its tenant's own event store, so a course opened in one tenant says nothing about
 * the same course identifier in another tenant.
 */
public class CourseNotOpenException extends RuntimeException {

    /**
     * Constructs the exception for the given {@code courseId} that was not opened.
     *
     * @param courseId the identifier of the course that was not opened
     */
    public CourseNotOpenException(String courseId) {
        super("Course [" + courseId + "] is not open for enrollment");
    }
}
