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

import java.util.Set;

/**
 * A course as a stored snapshot captured it, for reading a snapshot back outside the framework.
 * <p>
 * It carries the same components under the same names as the course entity, which is what lets the
 * application's {@code Converter} read a stored snapshot into it.
 * <p>
 * A snapshot captures the state the triggering load <em>sourced</em>, not the state the command that
 * triggered it leaves behind, so the students here are those enrolled before that command's own event.
 *
 * @param open             whether the course was open
 * @param capacity         how many seats the course offered
 * @param enrolledStudents the students enrolled at the position the snapshot was taken
 */
public record CourseSnapshot(boolean open, int capacity, Set<String> enrolledStudents) {

    public CourseSnapshot {
        enrolledStudents = enrolledStudents == null ? Set.of() : Set.copyOf(enrolledStudents);
    }
}
