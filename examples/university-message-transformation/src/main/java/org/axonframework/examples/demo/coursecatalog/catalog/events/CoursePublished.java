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

package org.axonframework.examples.demo.coursecatalog.catalog.events;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.catalog.values.CapacityRange;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * A course was published in the catalog. Current shape (v3): capacity is a
 * {@link CapacityRange} value object.
 *
 * @param catalogId the catalog the course belongs to
 * @param courseId  the published course
 * @param name      the course's display name
 * @param range     min/max enrolment the course accepts
 */
@Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "CoursePublished", version = "3.0.0")
public record CoursePublished(
        @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
        @EventTag(key = CourseCatalogTags.COURSE_ID) CourseId courseId,
        String name,
        CapacityRange range
) {
}
