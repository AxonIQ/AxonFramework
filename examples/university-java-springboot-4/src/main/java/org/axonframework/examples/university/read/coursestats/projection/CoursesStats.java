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

package org.axonframework.examples.university.read.coursestats.projection;

import org.axonframework.examples.university.shared.CourseId;

/**
 * Read model representing statistics about a course.
 *
 * @param courseId           course courseId.
 * @param name               name of the course.
 * @param capacity           capacity of the course.
 * @param subscribedStudents number of subscribed students.
 */
public record CoursesStats(CourseId courseId, String name, int capacity, int subscribedStudents) {

    public CoursesStats name (String name){
        return new CoursesStats(courseId, name, capacity, subscribedStudents);
    }

    public CoursesStats capacity ( int capacity){
        return new CoursesStats(courseId, name, capacity, subscribedStudents);
    }

    public CoursesStats subscribedStudents ( int subscribedStudents){
        return new CoursesStats(courseId, name, capacity, subscribedStudents);
    }
}
