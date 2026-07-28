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

import java.time.Duration;
import java.util.List;

/**
 * The machinery a store runs on, and the three ways a run may break it.
 * <p>
 * <b>Every fault in this suite before this interface existed perturbed an in-heap wrapper.</b> A hook that refuses a
 * commit, drops a batch or stalls a thread reaches the framework through a seam the harness owns, which means the
 * framework never sees anything a real deployment would: no half-written socket, no connection pool full of dead
 * handles, no process that stopped answering and then came back with its memory intact. Those are the failures a
 * distributed system is actually operated through, and none of them is expressible in the heap.
 * <p>
 * The three primitives here are chosen because they are genuinely different failures rather than three settings of one:
 * <ul>
 *     <li>{@link #interruptConnections(Duration)} leaves the store running and takes the network away. The store keeps
 *     its state and its clock; the application's connections die mid-statement and it cannot tell a lost reply from a
 *     lost request. This is the shape a commit acknowledgement is ambiguous in.</li>
 *     <li>{@link #kill()} takes the process away without warning. Anything not durable is gone, and the recovery on the
 *     next start is real recovery rather than a clean start.</li>
 *     <li>{@link #pause(Duration)} leaves the process and the network intact and stops it running. Nothing fails,
 *     nothing recovers, and every timeout in the system fires anyway. A kill can never produce this, and it is the
 *     shape of the long garbage collection that has broken more consensus implementations than any crash.</li>
 * </ul>
 * <b>Landing evidence is a return value, not a side effect.</b> Each method returns what it observed from the
 * infrastructure's own answer -- a proxy's reported state, a container's exit code, a restart line out of the store's own
 * log with a timestamp in it. A fault that cannot produce one has not landed, and a run under a fault that did not land
 * is inconclusive rather than a pass.
 * <p>
 * A backend with nothing to break returns {@link #none()}, whose {@link #available()} is {@code false}. A fault aimed at
 * infrastructure that does not exist records nothing, so the same scenario on an in-heap store reports a fault that never
 * fired rather than a quiet pass.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface StoreInfrastructure {

    /**
     * Indicates whether this store has infrastructure a run can break.
     *
     * @return {@code true} when the three primitives do something
     */
    default boolean available() {
        return true;
    }

    /**
     * Cuts every connection between the application and the store, and refuses new ones, for the given duration.
     * <p>
     * The store itself keeps running throughout, which is the point: this is a network failure and not a store failure,
     * so an in-flight commit may well have been applied while the application will never learn that it was.
     *
     * @param duration how long the connections stay cut; the implementation heals itself afterwards
     * @return what the infrastructure reported, which is this fault's landing evidence
     */
    Evidence interruptConnections(Duration duration);

    /**
     * Kills the store's process outright and brings it back.
     *
     * @param downtime how long the store stays down before it is started again
     * @return what the infrastructure reported, including the process's exit code and its recovery log line
     */
    Evidence kill(Duration downtime);

    /**
     * Freezes the store's process, without killing it or touching the network, for the given duration.
     * <p>
     * A frozen process holds every lock and every open transaction it had, answers nothing, and then continues from
     * exactly where it stopped. No error is raised anywhere and every deadline in the system expires regardless.
     *
     * @param duration how long the store stays frozen
     * @return what the infrastructure reported, including the paused state the container itself reported
     */
    Evidence pause(Duration duration);

    /**
     * Returns the inert infrastructure of a store that has none.
     *
     * @return an infrastructure whose primitives do nothing and report nothing
     */
    static StoreInfrastructure none() {
        return Inert.INSTANCE;
    }

    /**
     * What the infrastructure itself reported about one act of disruption.
     * <p>
     * A fault reads {@link #landed()} to decide whether to count itself as fired, and puts {@link #describe()} into the
     * history so that a reader can check the claim against the infrastructure's own words rather than the harness's.
     *
     * @param landed   whether the disruption demonstrably took effect
     * @param facts    what the infrastructure answered, one entry per observation, in the order they were made
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    record Evidence(boolean landed, List<String> facts) {

        /**
         * Compact constructor copying the facts defensively.
         */
        public Evidence {
            facts = List.copyOf(java.util.Objects.requireNonNull(facts, "The facts cannot be null."));
        }

        /**
         * Returns evidence that nothing happened.
         *
         * @param why what stopped the disruption from taking effect
         * @return evidence reporting no landing
         */
        public static Evidence missed(String why) {
            return new Evidence(false, List.of(why));
        }

        /**
         * Returns the observations, joined for a fault's target string and a history record.
         *
         * @return the facts, separated by semicolons
         */
        public String describe() {
            return String.join("; ", facts);
        }
    }

    /**
     * The infrastructure of a store that lives in the heap: there is nothing to disconnect, kill or freeze.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    final class Inert implements StoreInfrastructure {

        private static final Inert INSTANCE = new Inert();

        private static final Evidence NOTHING =
                Evidence.missed("the store has no infrastructure to break");

        private Inert() {
            // Singleton.
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Evidence interruptConnections(Duration duration) {
            return NOTHING;
        }

        @Override
        public Evidence kill(Duration downtime) {
            return NOTHING;
        }

        @Override
        public Evidence pause(Duration duration) {
            return NOTHING;
        }
    }
}
