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

import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;

import java.util.List;
import java.util.Map;

/**
 * The load a scenario puts on the framework.
 * <p>
 * A workload owns three things: the command handlers that write, the projection that reads, and the shape of the
 * traffic between them. It owns none of the judging: whatever it does, it records, and the checkers decide.
 * <p>
 * Adding a workload adds a class. A scenario names the instance it wants, so nothing existing changes.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface Workload {

    /**
     * Returns the workload's identifier, recorded in the history header.
     *
     * @return the identifier
     */
    String id();

    /**
     * Returns the resolver deciding which tags this workload's events carry.
     * <p>
     * Tags are what a Dynamic Consistency Boundary is drawn around, so this is the workload's declaration of what its
     * consistency boundaries can be.
     *
     * @return the tag resolver
     */
    TagResolver tagResolver();

    /**
     * Returns the workload's shape for the given seed, for the history header.
     *
     * @param seed     the seed fixing the shape
     * @param commands how many commands the run will issue
     * @return the shape, rendered flat
     */
    Map<String, String> describe(long seed, int commands);

    /**
     * Returns the names of the participants a fault may target.
     *
     * @param seed     the seed fixing the shape
     * @param commands how many commands the run will issue
     * @param mode     how much of the run's scheduling is pinned down, which decides how many participants there are
     * @return the participant names, in a stable order
     */
    List<String> participants(long seed, int commands, org.axonframework.hunt.harness.DeterminismMode mode);

    /**
     * Registers the workload's command handlers and returns the component that builds its projection.
     * <p>
     * Called once, after the store and the command bus exist and before the streaming processor is started.
     *
     * @param context everything the workload may reach
     * @return the event-handling component the run's processor will drive
     */
    EventHandlingComponent install(WorkloadContext context);

    /**
     * Issues the load and returns once every command has been dispatched and settled.
     *
     * @param context everything the workload may reach
     * @throws InterruptedException if the run is interrupted while dispatching
     */
    void run(WorkloadContext context) throws InterruptedException;

    /**
     * Indicates whether the projection has caught up with everything the run committed.
     * <p>
     * The runner waits for this before letting any oracle look at the history, because judging a system that is still
     * catching up manufactures violations at the run boundary.
     *
     * @param context everything the workload may reach
     * @return {@code true} when the read side has caught up with the write side
     */
    boolean quiesced(WorkloadContext context);

    /**
     * Writes the workload's final read-model state into the history, so a checker can compare it against its own fold
     * of what the run committed.
     *
     * @param context everything the workload may reach
     */
    void recordFinalState(WorkloadContext context);
}
