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

package org.axonframework.examples.springcloud.domain;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link CourseCommandHandlers} and {@link CourseQueryHandlers} directly against a shared
 * {@link CourseCatalog}, bypassing the message bus. This covers the module's one piece of actual business logic;
 * the Spring Cloud distribution itself is verified manually through the docker-compose setup described in the
 * module README.
 */
class CourseCatalogTest {

    private static final String NODE_NAME = "courses-test";

    private final List<Object> appendedEvents = new ArrayList<>();
    private final EventAppender eventAppender = new EventAppender() {
        @Override
        public void append(List<?> events) {
            appendedEvents.addAll(events);
        }

        @Override
        public void append(List<?> events, Metadata metadata) {
            appendedEvents.addAll(events);
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // Not needed for this test.
        }
    };

    private CourseCommandHandlers commandHandlers;
    private CourseQueryHandlers queryHandlers;

    @BeforeEach
    void setUp() {
        CourseCatalog catalog = new CourseCatalog(NODE_NAME);
        commandHandlers = new CourseCommandHandlers(catalog);
        queryHandlers = new CourseQueryHandlers(catalog);
    }

    @Nested
    class Create {

        @Test
        void createsCourseHandledByThisNode() {
            // when
            Course course = commandHandlers.create(new CreateCourse("axon-5", "Axon Framework 5"));

            // then
            assertThat(course).isEqualTo(new Course("axon-5", "Axon Framework 5", NODE_NAME));
            assertThat(queryHandlers.find(new FindCourse("axon-5"))).isEqualTo(course);
        }
    }

    @Nested
    class Rename {

        @Test
        void appendsCourseRenamedAndUpdatesTheCatalog() {
            // given
            commandHandlers.create(new CreateCourse("axon-5", "Axon Framework 5"));

            // when
            Course renamed = commandHandlers.rename(new RenameCourse("axon-5", "Axon Framework"), eventAppender);

            // then
            assertThat(renamed).isEqualTo(new Course("axon-5", "Axon Framework", NODE_NAME));
            assertThat(appendedEvents).containsExactly(new CourseRenamed("axon-5", "Axon Framework"));
            assertThat(queryHandlers.find(new FindCourse("axon-5"))).isEqualTo(renamed);
        }

        @Test
        void unknownCourseThrowsAndAppendsNoEvent() {
            // when / then
            assertThatThrownBy(() -> commandHandlers.rename(new RenameCourse("missing", "x"), eventAppender))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
            assertThat(appendedEvents).isEmpty();
        }
    }

    @Nested
    class Find {

        @Test
        void unknownCourseThrows() {
            // when / then
            assertThatThrownBy(() -> queryHandlers.find(new FindCourse("missing")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        }
    }
}
