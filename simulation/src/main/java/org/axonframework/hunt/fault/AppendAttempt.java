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

package org.axonframework.hunt.fault;

import java.util.List;
import java.util.Objects;

/**
 * What a {@link StoreHook} is told about the append it may interfere with.
 * <p>
 * Deliberately framework-free: a hook sees identifiers and a size, never an {@code EventMessage}. That keeps every
 * fault independent of the store it runs against, which is what lets the same fault run at every layer of the suite.
 *
 * @param participant the workload participant issuing the append, for example {@code writer-3}
 * @param eventIds    the identifiers of the events offered, in offer order
 * @param sequence    how many appends this store has seen before this one, counted from zero
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record AppendAttempt(String participant, List<String> eventIds, long sequence) {

    /**
     * Compact constructor rejecting missing parts and defensively copying the identifiers.
     */
    public AppendAttempt {
        Objects.requireNonNull(participant, "The participant cannot be null.");
        eventIds = List.copyOf(Objects.requireNonNull(eventIds, "The eventIds cannot be null."));
    }

    /**
     * Returns how many events the append offered.
     *
     * @return the batch size
     */
    public int batchSize() {
        return eventIds.size();
    }

    /**
     * Returns a short description of the batch, for use as a fault's landing-evidence target.
     *
     * @return the participant and the first event identifier of the batch
     */
    public String describe() {
        return participant + "/" + (eventIds.isEmpty() ? "empty-batch" : eventIds.getFirst());
    }
}
