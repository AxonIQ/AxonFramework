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

package org.axonframework.examples.university.write.createcourse;


import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.university.event.CourseCreated;
import org.axonframework.examples.university.shared.CourseId;
import org.axonframework.test.extension.AxonFrameworkExtension;
import org.axonframework.test.extension.AxonTestFixtureProvider;
import org.axonframework.test.extension.ProvidedAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture.Customization;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

@ExtendWith(AxonFrameworkExtension.class)
class CourseCreationTest {

    @ProvidedAxonTestFixture
    AxonTestFixtureProvider fixtureProvider = () -> AxonTestFixture.with(
            CreateCourseConfiguration.configure(EventSourcingConfigurer.create())
    );

    @Test
    void givenNotExistingCourse_WhenCreateCourse_ThenSuccess(AxonTestFixture fixture) {
        var courseId = CourseId.random();
        var courseName = "Event Sourcing in Practice";
        var capacity = 3;

        fixture.given()
               .when()
               .command(new CreateCourse(courseId, courseName, capacity))
               .then()
               .success()
               .events(new CourseCreated(courseId, courseName, capacity));
    }
}
