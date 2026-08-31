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

package org.axonframework.integrationtests.modelling.saga;

import java.util.ArrayList;
import java.util.List;

/**
 * Saga used to verify that a saga store round-trips saga state.
 * <p>
 * A plain bean, so it converts with any Jackson generation without needing annotations, carrying state so that a load
 * can assert the saga survived conversion rather than merely that something came back.
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
     * Sets the recorded events.
     *
     * @param handledEvents the recorded events
     */
    public void setHandledEvents(List<String> handledEvents) {
        this.handledEvents = handledEvents;
    }
}
