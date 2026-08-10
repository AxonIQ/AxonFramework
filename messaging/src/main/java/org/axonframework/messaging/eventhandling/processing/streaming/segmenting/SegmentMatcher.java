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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting;

import org.axonframework.common.annotation.Internal;

import java.util.Objects;

/**
 * Utility class that matches a resolved sequence identifier against a {@link Segment}.
 * <p>
 * The sequence identifier is expected to be resolved through
 * {@link org.axonframework.messaging.eventhandling.EventHandlingComponent#sequenceIdentifierFor(org.axonframework.messaging.eventhandling.EventMessage,
 * org.axonframework.messaging.core.unitofwork.ProcessingContext)} before matching, so any fallback for a
 * {@link org.axonframework.messaging.core.sequencing.SequencingPolicy} that resolves no identifier is applied in a
 * single place. This guarantees that scheduling and handling route an event to the same segment.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
public final class SegmentMatcher {

    private SegmentMatcher() {
        // Utility class
    }

    /**
     * Checks whether the given {@code segment} matches the given {@code sequenceIdentifier}, based on the
     * {@link Objects#hashCode(Object) hash code} of the identifier.
     *
     * @param segment            the segment to match against
     * @param sequenceIdentifier the resolved sequence identifier of the event to match
     * @return {@code true} if the sequence identifier matches the segment, {@code false} otherwise
     */
    public static boolean matches(Segment segment, Object sequenceIdentifier) {
        Objects.requireNonNull(segment, "Segment may not be null");
        Objects.requireNonNull(sequenceIdentifier, "Sequence identifier may not be null");
        return segment.matches(Objects.hashCode(sequenceIdentifier));
    }
}
