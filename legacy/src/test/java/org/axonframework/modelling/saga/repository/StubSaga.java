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

package org.axonframework.modelling.saga.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Saga used to verify that a {@link SagaStore} round-trips saga state.
 * <p>
 * It carries mutable state so that a load can assert more than "something came back": {@link #handled(String)} records
 * an entry, and {@link #equals(Object)} compares the recorded list, so a stored and reloaded instance is only equal to
 * the original if its state survived conversion.
 *
 * @author Mateusz Nowak
 */
public class StubSaga {

    private List<String> handledEvents = new ArrayList<>();

    /**
     * Records that the given {@code event} was handled, mutating this saga's state.
     *
     * @param event the event to record
     */
    public void handled(String event) {
        handledEvents.add(event);
    }

    /**
     * Returns the events recorded through {@link #handled(String)}, in order.
     *
     * @return the recorded events
     */
    public List<String> getHandledEvents() {
        return handledEvents;
    }

    /**
     * Sets the recorded events. Present so that this saga is a plain bean, and therefore converts with any Jackson
     * generation without needing annotations.
     *
     * @param handledEvents the recorded events
     */
    public void setHandledEvents(List<String> handledEvents) {
        this.handledEvents = handledEvents;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return Objects.equals(handledEvents, ((StubSaga) other).handledEvents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handledEvents);
    }

    @Override
    public String toString() {
        return "StubSaga{handledEvents=" + handledEvents + "}";
    }
}
