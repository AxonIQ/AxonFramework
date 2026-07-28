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

package org.axonframework.examples.demo.multitenancy.university;

/**
 * The event tag keys of the university domain, kept in one place so the events, the event-sourced
 * entities, and the command handlers that inject them all agree on the same key.
 */
public final class UniversityTags {

    /**
     * The course identifier, used in three agreeing roles: the {@code @EventTag} key on the events, the
     * {@code tagKey} an entity is sourced by, and a command's {@code @InjectEntity} id property. They
     * coincide as the same string on purpose, so an entity is sourced by exactly the events tagged with
     * the handling command's course.
     */
    public static final String COURSE_ID = "courseId";

    private UniversityTags() {
        // Utility class, not meant to be instantiated.
    }
}
