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

package org.axonframework.examples.university;

import org.axonframework.examples.university.event.CourseCreated;
import org.axonframework.examples.university.shared.CourseId;
import org.axonframework.examples.university.write.createcourse.CreateCourse;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

@AxonSpringBootTest(properties = "axon.axonserver.enabled=false")
class CreateCourseCommandIT {

    @Autowired
    private AxonTestFixture fixture;

    @Nested
    class CreateCourseCommand {

        @Test
        void createsCourseSuccessfully() {
            var courseId = new CourseId("course-1");

            fixture.given()
                   .noPriorActivity()
                   .when()
                   .command(new CreateCourse(courseId, "Introduction to Axon Framework", 30))
                   .then()
                   .success()
                   .events(new CourseCreated(courseId, "Introduction to Axon Framework", 30));
        }
    }
}
