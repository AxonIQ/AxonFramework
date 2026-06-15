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
 * An operational liveness ping from a retired monitoring sidecar. The catalog projection counts the
 * heartbeats it sees, but the {@code SystemHeartbeatDrop} transformation removes these pings on read,
 * so that count stays at zero while the pings remain in storage.
 *
 * @param catalogId the catalog the ping belongs to
 * @param sequence  the ping's sequence number
 */
@Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "SystemHeartbeat", version = "1.0.0")
public record SystemHeartbeat(
        @EventTag(key = CourseCatalogTags.CATALOG_ID) CatalogId catalogId,
        int sequence
) {
}
