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

package org.axonframework.examples.demo.multitenancy.university.write.course;

import org.axonframework.eventsourcing.annotation.EventTag;

/**
 * Event marking a course as opened with a fixed number of seats. Tagged by course identifier, so the
 * {@link Course} entity sources it from its tenant's own event store. The tenant it belongs to is not
 * part of the event: it is the store the event lands in, resolved from the message being handled.
 *
 * @param courseId the identifier of the opened course
 * @param capacity the number of seats the course offers
 */
public record CourseOpened(@EventTag(key = CourseTags.COURSE_ID) String courseId, int capacity) {

}
