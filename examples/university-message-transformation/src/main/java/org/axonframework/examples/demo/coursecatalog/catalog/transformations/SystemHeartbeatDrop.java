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
import org.axonframework.messaging.core.MessageType;

/**
 * Drops the {@code SystemHeartbeat} event. An old monitoring sidecar wrote these liveness pings into
 * the catalog stream; the projection still counts them, but they are operational noise that should be
 * suppressed from processing. {@link EventTransformation#drop(MessageType)} removes every matching
 * event from the read stream while leaving it untouched in storage, so the projection's heartbeat
 * count stays at zero without rewriting history.
 * <p>
 * Surviving events keep their tracking token, so once a streaming processor reads an event after a
 * drop the dropped position is covered and not revisited. Matching is by exact identity, like a
 * concrete {@code from(...)}; a drop takes no predicate.
 */
public final class SystemHeartbeatDrop {

    private static final MessageType FROM =
            new MessageType(CourseCatalogMessageNames.SYSTEM_HEARTBEAT, "1.0.0");

    private SystemHeartbeatDrop() {
    }

    /** @return the transformation registered into the chain */
    public static EventTransformation build() {
        return EventTransformation.drop(FROM);
    }
}
