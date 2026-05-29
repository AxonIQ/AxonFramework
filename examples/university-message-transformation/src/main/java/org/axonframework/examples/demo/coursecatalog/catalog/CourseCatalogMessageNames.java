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

package org.axonframework.examples.demo.coursecatalog.catalog;

/**
 * Single source of truth for event qualified names. Used by every
 * {@code @Event} annotation on the current event records and by every
 * {@code MessageType} constructed inside a transformation; pinning the strings
 * here keeps the chain and the seeded payloads in sync.
 */
public final class CourseCatalogMessageNames {

    /** Namespace prefix; combined with the local name via {@code "."} forms the qualified name. */
    public static final String NAMESPACE = "coursecatalog";

    /** Qualified name of {@code CoursePublished}. */
    public static final String COURSE_PUBLISHED          = NAMESPACE + ".CoursePublished";
    /** Qualified name of {@code CourseCapacityChanged}. */
    public static final String COURSE_CAPACITY_CHANGED   = NAMESPACE + ".CourseCapacityChanged";
    /** Qualified name of {@code StudentRegistered}. */
    public static final String STUDENT_REGISTERED        = NAMESPACE + ".StudentRegistered";
    /** Qualified name of {@code StudentEnrolledInCourse}. */
    public static final String STUDENT_ENROLLED_IN_COURSE = NAMESPACE + ".StudentEnrolledInCourse";
    /** Qualified name of {@code RegistrationClosed}. */
    public static final String REGISTRATION_CLOSED       = NAMESPACE + ".RegistrationClosed";
    /** Qualified name of {@code SystemAnnouncement}. */
    public static final String SYSTEM_ANNOUNCEMENT       = NAMESPACE + ".SystemAnnouncement";
    /** Qualified name of {@code WelcomeMessageSent}. */
    public static final String WELCOME_MESSAGE_SENT      = NAMESPACE + ".WelcomeMessageSent";
    /** Qualified name of {@code CatalogSeeded}; written by the seeder as an idempotency marker. */
    public static final String CATALOG_SEEDED            = NAMESPACE + ".CatalogSeeded";

    private CourseCatalogMessageNames() {
    }
}
