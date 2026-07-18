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
import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * A catalog-wide operational notice. Lifted by the chain from the historic
 * unversioned (default {@code 0.0.1}) shape.
 *
 * @param catalogId the catalog the announcement belongs to
 * @param text      the announcement body
 */
@Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "SystemAnnouncement", version = "1.0.0")
public record SystemAnnouncement(
        @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
        String text
) {
}
