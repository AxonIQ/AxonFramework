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

package org.axonframework.hunt.workload;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.hunt.fault.PausePoint;
import org.axonframework.hunt.harness.Deadline;
import org.axonframework.hunt.harness.HuntWorld;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.messaging.commandhandling.CommandBus;

import java.util.Objects;

/**
 * Everything a workload is allowed to reach.
 * <p>
 * Handing the workload a context rather than the running system is what keeps a workload portable: the same class
 * drives a single-process simulation and, later, a run against a real store, because it never learns which it is.
 *
 * @param world    the wired system: the command bus, the event store, and the seams a fault reaches
 * @param seed     the seed fixing this run's shape
 * @param commands how many commands this run should issue
 * @param recorder the recorder the workload writes its own operations to
 * @param deadline the wall-clock stop; a workload checks it rather than assuming it will finish
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record WorkloadContext(HuntWorld world,
                              long seed,
                              int commands,
                              HistoryRecorder recorder,
                              Deadline deadline) {

    /**
     * Compact constructor rejecting missing parts.
     */
    public WorkloadContext {
        Objects.requireNonNull(world, "The world cannot be null.");
        Objects.requireNonNull(recorder, "The recorder cannot be null.");
        Objects.requireNonNull(deadline, "The deadline cannot be null.");
    }

    /**
     * Returns the run's command bus.
     *
     * @return the command bus
     */
    public CommandBus commandBus() {
        return world.commandBus();
    }

    /**
     * Returns the run's event store.
     *
     * @return the event store
     */
    public EventStore eventStore() {
        return world.eventStore();
    }

    /**
     * Returns how much of the run's scheduling is pinned down.
     *
     * @return the determinism mode
     */
    public org.axonframework.hunt.harness.DeterminismMode determinism() {
        return world.determinism();
    }

    /**
     * Returns the seam a pause fault stalls a participant through.
     *
     * @return the pause point
     */
    public PausePoint pauses() {
        return world.pauses();
    }
}
