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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.annotation.Internal;

import java.util.List;

/**
 * Coordinates appended events notifications for event storage engines.
 * <p>
 * Implementations can notify only within the current process, or coordinate between multiple instances using mechanisms
 * such as polling, messaging, or database notifications.
 *
 * @author John Hendrikx
 * @since 5.0.0
 */
@Internal
public interface EventCoordinator {

    /**
     * A coordinator that only forwards append notifications within a single event storage engine. It does not
     * coordinate between multiple instances.
     */
    public static EventCoordinator SIMPLE = callback -> new Handle() {
        @Override
        public void onEventsAppended(List<TaggedEventMessage<?>> events) {
            callback.run();
        }

        @Override
        public void terminate() {
        }
    };

    /**
     * Starts a coordination instance that will invoke the given callback when new events are appended.
     * <p>
     * The callback may be invoked on an arbitrary thread. Implementations should ensure the callback does not perform
     * long-running or blocking operations. If the callback throws an exception, the coordination is terminated.
     *
     * @param onAppendDetected the callback to invoke when new events are detected; must not be {@code null}
     * @return a handle to interact with the coordination instance, including notifying of new events and terminating
     * the coordination; never {@code null}
     */
    Handle startCoordination(Runnable onAppendDetected);

    /**
     * Represents a handle to a coordination instance, allowing the engine to notify of new events and to terminate the
     * coordination.
     */
    interface Handle {

        /**
         * Invoked by the storage engine when new events have been appended.
         * <p>
         * This method may be called concurrently from multiple threads. Implementations should avoid heavy or blocking
         * operations.
         *
         * @param events the events that were appended; never {@code null} or empty
         */
        void onEventsAppended(List<TaggedEventMessage<?>> events);

        /**
         * Terminates this coordination instance, releasing any resources or threads used internally. Blocks
         * until termination completes.
         * <p>
         * After termination, the handle should not be used again.
         */
        void terminate();
    }
}
