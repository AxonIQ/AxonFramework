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

package org.axonframework.examples.demo.multitenancy.university.events;

import org.axonframework.examples.demo.multitenancy.university.UniversityTags;
import org.axonframework.eventsourcing.annotation.EventTag;

/**
 * Event recording that a student took a seat in a course. Tagged by course identifier, so it is sourced
 * back into the course entity that summarizes the seats taken so far, from that tenant's own event
 * store.
 *
 * @param courseId  the identifier of the course enrolled in
 * @param studentId the identifier of the enrolled student
 */
public record StudentEnrolledInCourse(@EventTag(key = UniversityTags.COURSE_ID) String courseId,
                                      String studentId) {

}
