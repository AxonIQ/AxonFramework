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

package org.axonframework.hunt.harness;

import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;

/**
 * The framework's own in-heap storage engine, which speaks the Dynamic Consistency Boundary protocol natively.
 * <p>
 * It is the backend every scenario can assume exists, and the one a finding is first attributed against: a defect
 * that reproduces here is in the framework rather than in a store adapter.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class InMemoryHuntBackend implements HuntBackend {

    /**
     * The name this backend is selected by in a scenario record.
     */
    public static final String NAME = "in-memory";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EventStorageEngine createEngine() {
        return new InMemoryEventStorageEngine();
    }
}
