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

package org.axonframework.examples.demo.coursecatalog.catalog.seed;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CatalogSeeded;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.UUID;

/**
 * Writes the historic events that pretend to have been in the store for years,
 * so the application boots into a state with v1/v2 {@code CoursePublished}
 * payloads, v1 {@code StudentRegistered} payloads, beta-versioned
 * {@code WelcomeMessageSent} payloads, and an unversioned {@code SystemAnnouncement}
 * waiting for the transformation chain to lift them on read.
 * <p>
 * Idempotent: state-sourced from the catalog's {@link CatalogSeeded} marker. Calling
 * the {@link SeedCatalog} command a second time is a no-op.
 * <p>
 * The historic shapes live as private inner records, so the production package list
 * is not polluted with classes a user would mistake for the current shape.
 */
public class LegacyEventSeeder {

    @CommandHandler
    void handle(SeedCatalog command, @InjectEntity State state, EventAppender appender) {
        if (state.alreadySeeded) {
            return;
        }
        CatalogId catalogId = command.catalogId();
        appender.append(
                new CoursePublishedV1(catalogId, CourseId.of("event-sourcing-101"),
                                      "Event Sourcing in Practice", 30),
                new CoursePublishedV1(catalogId, CourseId.of("ddd-fundamentals"),
                                      "DDD Fundamentals", 25),
                new CoursePublishedV1(catalogId, CourseId.of("cqrs-deep-dive"),
                                      "CQRS Deep Dive", 20),

                new CoursePublishedV2(catalogId, CourseId.of("hexagonal-architecture"),
                                      "Hexagonal Architecture", 10, 30),
                new CoursePublishedV2(catalogId, CourseId.of("polyglot-storage"),
                                      "Polyglot Storage Strategies", 8, 24),

                new StudentRegisteredV1(catalogId, StudentId.of("alice"), "Alice", "Hopper"),
                new StudentRegisteredV1(catalogId, StudentId.of("bob"), "Bob", "Carter"),
                new StudentRegisteredV1(catalogId, StudentId.of("carol"), "Carol", "Davis"),
                new StudentRegisteredV1(catalogId, StudentId.of("dave"), "Dave", "Evans"),

                new WelcomeMessageSentV0_5(StudentId.of("alice"), "Welcome aboard!",
                                            "Welcome to the catalog, Alice."),
                new WelcomeMessageSentV0_7(StudentId.of("bob"), "Welcome!",
                                            "Hi Bob, welcome to the catalog."),
                new WelcomeMessageSentV0_9(StudentId.of("carol"), "Welcome",
                                            "Welcome Carol."),

                new SystemAnnouncementUnversioned(catalogId, "Catalog launched in 2023"),

                new CatalogSeeded(catalogId, UUID.randomUUID().toString())
        );
    }

    /**
     * Marker-event-sourced state, scoped to the catalog id so seeding can be
     * detected with a single tag-filtered read.
     */
    @EventSourcedEntity(tagKey = CourseCatalogTags.CATALOG_ID)
    static final class State {

        private boolean alreadySeeded;

        @EntityCreator
        State() {
        }

        @EventSourcingHandler
        void evolve(CatalogSeeded event) {
            this.alreadySeeded = true;
        }

        @EventCriteriaBuilder
        private static EventCriteria resolveCriteria(CatalogId id) {
            return EventCriteria
                    .havingTags(Tag.of(CourseCatalogTags.CATALOG_ID, id.toString()))
                    .andBeingOneOfTypes(CourseCatalogMessageNames.CATALOG_SEEDED);
        }
    }

    // ------------------------------------------------------------------------
    // Historic event shapes. Each one carries the @Event identity of an event
    // that pretends to have been written years ago. Kept private to this seeder
    // so users browsing the codebase cannot mistake them for the current shape.
    // ------------------------------------------------------------------------

    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "CoursePublished", version = "1.0.0")
    record CoursePublishedV1(
            @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
            @EventTag(key = CourseCatalogTags.COURSE_ID) CourseId courseId,
            String name,
            int capacity
    ) {
    }

    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "CoursePublished", version = "2.0.0")
    record CoursePublishedV2(
            @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
            @EventTag(key = CourseCatalogTags.COURSE_ID) CourseId courseId,
            String name,
            int minCapacity,
            int maxCapacity
    ) {
    }

    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "StudentRegistered", version = "1.0.0")
    record StudentRegisteredV1(
            @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
            @EventTag(key = CourseCatalogTags.STUDENT_ID) StudentId studentId,
            String firstName,
            String lastName
    ) {
    }

    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "WelcomeMessageSent", version = "0.5")
    record WelcomeMessageSentV0_5(
            @EventTag(key = CourseCatalogTags.STUDENT_ID) StudentId studentId,
            String subject,
            String body
    ) {
    }

    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "WelcomeMessageSent", version = "0.7")
    record WelcomeMessageSentV0_7(
            @EventTag(key = CourseCatalogTags.STUDENT_ID) StudentId studentId,
            String subject,
            String body
    ) {
    }

    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "WelcomeMessageSent", version = "0.9")
    record WelcomeMessageSentV0_9(
            @EventTag(key = CourseCatalogTags.STUDENT_ID) StudentId studentId,
            String subject,
            String body
    ) {
    }

    /**
     * Unversioned {@code SystemAnnouncement}: omitting the {@code version} attribute on
     * {@link Event} makes it default to {@code 0.0.1}, which the
     * {@code SystemAnnouncementLegacyUplift} transformation lifts to {@code 1.0.0}.
     */
    @Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "SystemAnnouncement")
    record SystemAnnouncementUnversioned(
            @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
            String message
    ) {
    }
}
