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

package org.axonframework.examples.university.faculty.write.create_course

import org.axonframework.eventsourcing.annotation.EventSourcedEntity
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
import org.axonframework.examples.university.faculty.FacultyTags.COURSE
import org.axonframework.examples.university.faculty.events.CourseCreated

@EventSourcedEntity(tagKey = COURSE)
internal class CreateCourseState @EntityCreator constructor() {

    private var created: Boolean = false

    fun decide(command: CreateCourse): List<Any> {
        if (created) {
            return listOf()
        }
        return listOf(CourseCreated(command.courseId, command.name, command.capacity))
    }

    @EventSourcingHandler
    fun evolve(event: CourseCreated): CreateCourseState = apply {
        created = true
    }
}
