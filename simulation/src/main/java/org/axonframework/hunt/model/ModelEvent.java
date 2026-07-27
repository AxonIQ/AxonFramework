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

package org.axonframework.hunt.model;

import java.util.Objects;
import java.util.Set;

/**
 * An event as the reference model sees it: an identity, a type name, and the tags it is associated with.
 * <p>
 * The payload is deliberately absent. Nothing in the Dynamic Consistency Boundary protocol reads it, so modelling it
 * would only invite the model and the engine to disagree about something neither one decides on.
 *
 * @param id   the event's identifier, unique within a run
 * @param type the event type's qualified name
 * @param tags the tags the event carries
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record ModelEvent(String id, String type, Set<ModelTag> tags) {

    /**
     * Compact constructor rejecting missing parts and defensively copying the tags.
     */
    public ModelEvent {
        Objects.requireNonNull(id, "The id cannot be null.");
        Objects.requireNonNull(type, "The type cannot be null.");
        tags = Set.copyOf(Objects.requireNonNull(tags, "The tags cannot be null."));
    }
}
