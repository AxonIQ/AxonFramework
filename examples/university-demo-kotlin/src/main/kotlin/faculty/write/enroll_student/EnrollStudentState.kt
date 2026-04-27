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

package org.axonframework.examples.university.faculty.write.enroll_student

import org.axonframework.examples.university.faculty.FacultyTags
import org.axonframework.examples.university.faculty.events.StudentEnrolledInFaculty
import org.axonframework.eventsourcing.annotation.EventSourcedEntity
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator

@EventSourcedEntity(tagKey = FacultyTags.STUDENT)
internal class EnrollStudentState @EntityCreator constructor() {
    var enrolled = false

    fun decide(cmd: EnrollStudent): List<Any> = if (enrolled) {
        emptyList()
    } else {
        listOf(StudentEnrolledInFaculty(cmd.studentId, cmd.firstName, cmd.lastName))
    }

    @EventSourcingHandler
    fun evolve(evt: StudentEnrolledInFaculty) = apply {
        enrolled = true
    }
}
