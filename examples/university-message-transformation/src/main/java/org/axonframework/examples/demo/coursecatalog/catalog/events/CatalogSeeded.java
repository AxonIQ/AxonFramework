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
 * Marker event written by the legacy seeder. Its presence makes later runs
 * skip seeding, so the catalog stays stable across application restarts.
 *
 * @param catalogId the seeded catalog
 * @param seedRunId a fresh identifier for this seeding run, for diagnostics only
 */
@Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "CatalogSeeded", version = "1.0.0")
public record CatalogSeeded(
        @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
        String seedRunId
) {
}
