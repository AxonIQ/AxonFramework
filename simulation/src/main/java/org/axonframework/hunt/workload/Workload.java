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
     * Returns how many events this workload's projection has been handed so far.
     * <p>
     * The runner compares this against what the store says it holds, and waits for the two to meet before letting any
     * oracle look at the history, because judging a system that is still catching up manufactures violations at the run
     * boundary. It also watches this number for growth: a read side that has stopped moving while events are still
     * missing has lost them, and one that is still moving has not.
     * <p>
     * A workload reports what it was handed rather than deciding quiescence itself, because the other half of the
     * comparison is the store's own answer and no workload can give it. Deriving it from what the harness offered was
     * measured to make quiescence unreachable on a store that commits in two phases.
     *
     * @param context everything the workload may reach
     * @return the number of deliveries the projection has accepted, counting repeats
     */
    long deliveredEvents(WorkloadContext context);

    /**
     * Returns the identifiers of every event this workload's projection has been handed.
     * <p>
     * <b>Quiescence is a set question and counting it was measured to be wrong.</b> The store hands events out in its own
     * global order, and on a store whose index comes from a sequence taken before a transaction commits, one batch's rows
     * are routinely separated by another writer's. So a count can reach the store's count while an event is still
     * undelivered, and a run that stopped there folded a projection that was holding half a transfer -- measured, as a
     * balance mismatch on both PostgreSQL arms with no event lost at all. Asking which identifiers arrived cannot be
     * satisfied early.
     * <p>
     * {@link #deliveredEvents(WorkloadContext)} stays the progress signal and this one is the completeness signal: a set
     * that stops growing says nothing about repeats, and a count that keeps growing is what proves the read side is still
     * moving.
     *
     * @param context everything the workload may reach
     * @return the delivered identifiers; a live view is acceptable, so callers must not modify it
     */
    java.util.Set<String> deliveredEventIds(WorkloadContext context);

    /**
     * Writes the workload's final read-model state into the history, so a checker can compare it against its own fold
     * of what the run committed.
     *
     * @param context everything the workload may reach
     */
    void recordFinalState(WorkloadContext context);
}
