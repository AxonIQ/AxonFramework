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
 * Tag-key constants used by {@code @EventTag} on events and by
 * {@code EventCriteria} in DCB reads; pinned here so producers and consumers stay
 * in sync.
 */
public final class CourseCatalogTags {

    /** Tag scoping events to a single catalog. */
    public static final String CATALOG_ID = "catalogId";
    /** Tag scoping events to a single course. */
    public static final String COURSE_ID  = "courseId";
    /** Tag scoping events to a single student. */
    public static final String STUDENT_ID = "studentId";

    private CourseCatalogTags() {
    }
}
