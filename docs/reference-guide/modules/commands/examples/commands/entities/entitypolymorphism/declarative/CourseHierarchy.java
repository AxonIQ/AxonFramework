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
package commands.entities.entitypolymorphism.declarative;

// The hierarchy is nested in an interface so the classes are implicitly static: the page can then
// display them as top-level classes (indent=0) while OnlineCourse and InPersonCourse remain
// instantiable from CourseEntityConfiguration without an enclosing instance.
interface CourseHierarchy {

    // tag::declarative-hierarchy[]
    // Abstract parent, no annotations needed
    public abstract class CourseEntity {

        protected String courseId;
        protected String title;
        protected int capacity;
        protected int enrolledCount;
    }

    // Concrete subtypes
    public class OnlineCourse extends CourseEntity {

        protected String platformUrl;

        public OnlineCourse(CourseCreated event) {
            this.courseId = event.courseId();
            this.title = event.title();
            this.capacity = event.capacity();
            this.platformUrl = event.platformUrl();
        }
    }

    public class InPersonCourse extends CourseEntity {

        protected String location;

        public InPersonCourse(CourseCreated event) {
            this.courseId = event.courseId();
            this.title = event.title();
            this.capacity = event.capacity();
            this.location = event.location();
        }
    }
    // end::declarative-hierarchy[]
}
