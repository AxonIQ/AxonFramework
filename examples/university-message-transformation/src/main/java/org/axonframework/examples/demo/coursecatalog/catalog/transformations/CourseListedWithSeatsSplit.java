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

package org.axonframework.examples.demo.coursecatalog.catalog.transformations;

import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CourseCapacityChanged;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.messaging.core.MessageType;

/**
 * Splits a historic {@code CourseListedWithSeats} event, which bundled a course listing and its
 * initial seat count, into the two events that later modeled those facts separately: a
 * {@code CoursePublished} and a {@code CourseCapacityChanged}.
 * <p>
 * Every shape is a typed record, so the mapper reads and writes named, compile-checked fields. The stored
 * event is read into a {@link Listing} record. The produced {@code CoursePublished} is emitted at version
 * {@code 1.0.0} as a stored-shape {@link PublishedV1} record and then flows through the
 * {@code 1.0.0 -> 2.0.0 -> 3.0.0} chain to reach handlers as the current event, while the
 * {@code CourseCapacityChanged} is produced directly as the current event. Both produced types are declared
 * up front, so a type-filtering read is widened back to the source.
 */
public final class CourseListedWithSeatsSplit {

    private static final String VERSION_ONE = "1.0.0";
    private static final MessageType FROM =
            new MessageType(CourseCatalogMessageNames.COURSE_LISTED_WITH_SEATS, VERSION_ONE);
    private static final MessageType COURSE_PUBLISHED =
            new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, VERSION_ONE);
    private static final MessageType COURSE_CAPACITY_CHANGED =
            new MessageType(CourseCatalogMessageNames.COURSE_CAPACITY_CHANGED, VERSION_ONE);

    private CourseListedWithSeatsSplit() {
    }

    /** @return the transformation registered into the chain */
    public static EventTransformation build() {
        return EventTransformation.split(FROM, Listing.class)
                                  .producing(COURSE_PUBLISHED, CourseListedWithSeatsSplit::toPublished)
                                  .producing(COURSE_CAPACITY_CHANGED, CourseListedWithSeatsSplit::toCapacityChanged)
                                  .build();
    }

    private static PublishedV1 toPublished(Listing listing) {
        return new PublishedV1(listing.catalogId(), listing.courseId(), listing.name(), listing.seats());
    }

    private static CourseCapacityChanged toCapacityChanged(Listing listing) {
        return new CourseCapacityChanged(
                new CourseId(listing.courseId().value()), new CapacityRange(listing.seats(), listing.seats()));
    }

    // Stored shape of the bundled listing: identifiers were written as {"value": ...} objects.
    record Listing(Id catalogId, Id courseId, String name, int seats) {

    }

    // Stored shape of CoursePublished 1.0.0, the version the split emits before the chain lifts it.
    record PublishedV1(Id catalogId, Id courseId, String name, int capacity) {

    }

    // Stored shape of an identifier value object: a single value string.
    record Id(String value) {

    }
}
