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

import io.axoniq.framework.messaging.transformation.events.EventTransformer;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.messaging.core.MessageType;

/**
 * Lifts a v2 {@code CoursePublished} into the current v3 shape, wrapping
 * {@code minCapacity}/{@code maxCapacity} into a single {@code range}.
 * <p>
 * Type-safe variant: instead of reading an untyped {@code JsonNode}, it declares the stored v2
 * shape as a {@link V2Schema} {@code record} and maps it to the current {@link CoursePublished}
 * event. The mapper works on named, typed fields, so a misspelled field or wrong type is a
 * compile error. {@link V2Schema} only mirrors how v2 was stored - including identifiers written
 * as {@code {"value": ...}} objects - and stays free of the live value objects, which the mapper
 * builds once at the end.
 */
public final class CoursePublishedV2ToV3 {

    private static final MessageType FROM = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0");
    private static final MessageType TO   = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "3.0.0");

    private CoursePublishedV2ToV3() {
    }

    /** @return the transformer registered into the chain */
    public static EventTransformer build() {
        return EventTransformer.from(FROM).to(TO).transform(V2Schema.class, CoursePublishedV2ToV3::map);
    }

    private static CoursePublished map(V2Schema v2) {
        return new CoursePublished(
                new CatalogId(v2.catalogId().value()),
                new CourseId(v2.courseId().value()),
                v2.name(),
                new CapacityRange(v2.minCapacity(), v2.maxCapacity())
        );
    }

    /** Wire shape of a v2 {@code CoursePublished}: {@code capacity} split into a min and max. */
    record V2Schema(Id catalogId, Id courseId, String name, int minCapacity, int maxCapacity) {

    }

    /** Wire shape of a stored id value object: a single {@code value} string. */
    record Id(String value) {

    }
}
