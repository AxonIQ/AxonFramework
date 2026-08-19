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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CatalogSeeded;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CourseCapacityChanged;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.events.RegistrationClosed;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentEnrolledInCourse;
import org.axonframework.examples.demo.coursecatalog.catalog.events.StudentRegistered;
import org.axonframework.examples.demo.coursecatalog.catalog.events.SystemAnnouncement;
import org.axonframework.examples.demo.coursecatalog.catalog.events.WelcomeMessageSent;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against drift between the {@code @Event(namespace, name)} annotations on the
 * current event classes and the qualified-name string constants the chain matches on.
 * If anyone renames an annotation field or a constant in isolation, this test fails.
 */
class MessageTypesConsistencyTest {

    private final AnnotationMessageTypeResolver resolver = new AnnotationMessageTypeResolver();

    @Test
    void coursePublishedResolvesToConstant() {
        assertThat(qualifiedName(CoursePublished.class))
                .isEqualTo(CourseCatalogMessageNames.COURSE_PUBLISHED);
    }

    @Test
    void courseCapacityChangedResolvesToConstant() {
        assertThat(qualifiedName(CourseCapacityChanged.class))
                .isEqualTo(CourseCatalogMessageNames.COURSE_CAPACITY_CHANGED);
    }

    @Test
    void studentRegisteredResolvesToConstant() {
        assertThat(qualifiedName(StudentRegistered.class))
                .isEqualTo(CourseCatalogMessageNames.STUDENT_REGISTERED);
    }

    @Test
    void studentEnrolledInCourseResolvesToConstant() {
        assertThat(qualifiedName(StudentEnrolledInCourse.class))
                .isEqualTo(CourseCatalogMessageNames.STUDENT_ENROLLED_IN_COURSE);
    }

    @Test
    void registrationClosedResolvesToConstant() {
        assertThat(qualifiedName(RegistrationClosed.class))
                .isEqualTo(CourseCatalogMessageNames.REGISTRATION_CLOSED);
    }

    @Test
    void systemAnnouncementResolvesToConstant() {
        assertThat(qualifiedName(SystemAnnouncement.class))
                .isEqualTo(CourseCatalogMessageNames.SYSTEM_ANNOUNCEMENT);
    }

    @Test
    void welcomeMessageSentResolvesToConstant() {
        assertThat(qualifiedName(WelcomeMessageSent.class))
                .isEqualTo(CourseCatalogMessageNames.WELCOME_MESSAGE_SENT);
    }

    @Test
    void catalogSeededResolvesToConstant() {
        assertThat(qualifiedName(CatalogSeeded.class))
                .isEqualTo(CourseCatalogMessageNames.CATALOG_SEEDED);
    }

    private String qualifiedName(Class<?> eventClass) {
        Optional<MessageType> resolved = resolver.resolve(eventClass);
        assertThat(resolved).as("@Event annotation must resolve for %s", eventClass.getSimpleName()).isPresent();
        return resolved.get().qualifiedName().name();
    }
}
