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
package commands.entities.statefulcommandhandler;

// The import is indented to the depth of the nested records below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::course-commands-import[]
    import org.axonframework.modelling.annotation.TargetEntityId;
// end::course-commands-import[]

class CourseCommands {

    // tag::course-commands[]

    public record CreateCourse(String courseId, String name, int capacity) {}

    public record RenameCourse(@TargetEntityId String courseId, String name) {}
    // end::course-commands[]
}
