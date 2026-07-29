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

package org.axonframework.eventsourcing.eventstore.jpa;

import jakarta.persistence.EntityManager;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.eventsourcing.eventstore.EventCoordinator;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link EventCoordinator} implementation that polls a JPA-managed event table
 * to detect newly appended events.
 * <p>
 * This coordinator periodically counts all entries in the event table. If the total
 * number of events changes since the last poll, it triggers the provided callback.
 *
 * @author John Hendrikx
 * @since 5.0.0
 */
@Internal
public class JpaPollingEventCoordinator implements EventCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(JpaPollingEventCoordinator.class);
    private static final ThreadFactory THREAD_FACTORY = Thread.ofPlatform()
        .daemon()
        .name(JpaPollingEventCoordinator.class.getSimpleName(), 1)
        .factory();

    private static final String COUNT_EVENTS =
        """
        SELECT COUNT(*) FROM AggregateEventEntry
        """;

    /**
     * The bound on how long {@link Handle#terminate()} waits for the polling thread to return.
     * <p>
     * A poll blocks in a JDBC call that does not respond to an interrupt, so waiting without a deadline hands the
     * caller of {@code terminate()} - a shutdown sequence, or a test tearing down its context - the same unbounded
     * wait as the query it happens to have caught. The polling thread is a daemon, so giving up on it costs nothing
     * beyond the query it is still running.
     */
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5);

    private final EntityManagerProvider entityManagerProvider;
    private final Duration pollingInterval;

    /**
     * Creates a new JPA polling event coordinator.
     *
     * @param entityManagerProvider provides {@link EntityManager} instances for querying the event table; must not be {@code null}
     * @param pollingInterval the duration between polling cycles; must be positive and not {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code pollingInterval} is not positive
     */
    public JpaPollingEventCoordinator(
        EntityManagerProvider entityManagerProvider,
        Duration pollingInterval
    ) {
        this.entityManagerProvider = Objects.requireNonNull(entityManagerProvider, "entityManagerProvider");
        this.pollingInterval = Objects.requireNonNull(pollingInterval, "pollingInterval");

        if (!pollingInterval.isPositive()) {
            throw new IllegalArgumentException("pollingInterval must be positive: " + pollingInterval);
        }
    }

    /*
     * Interrupts are used in two ways:
     *
     * - To immediately trigger a notification when onEventsAppended is called.
     * - To terminate the polling thread when terminate is invoked.
     *
     * The interrupt is only an accelerator for the second case, never the condition itself: the
     * callback runs arbitrary code that may consume the interrupt before the loop observes it, and a
     * poll blocked in JDBC does not observe it at all. The terminated flag is therefore checked on
     * every iteration, and terminate waits for the thread only up to TERMINATION_TIMEOUT, so neither
     * a swallowed interrupt nor a slow query can leave terminate blocked forever.
     *
     * The COUNT(*) query is intentionally used for the JPA variant as it does not
     * have a gapless monotonic index that can be relied on.
     *
     * Exceptions during polling are logged and do not terminate the coordination; the next
     * poll will retry. However, if the callback itself throws an exception, coordination
     * is terminated.
     */

    @Override
    public Handle startCoordination(Runnable onAppendDetected) {
        AtomicBoolean terminated = new AtomicBoolean();
        Thread pollingThread = THREAD_FACTORY.newThread(() -> {
            long lastTotalEvents = 0;

            while (!terminated.get()) {
                try {
                    Thread.sleep(pollingInterval);

                    long totalEvents = countEvents();

                    if (totalEvents == lastTotalEvents) {
                        continue;
                    }

                    lastTotalEvents = totalEvents;
                }
                catch (InterruptedException e) {
                    // Received request to stop or to recheck
                    if (terminated.get()) {
                        break;
                    }
                }
                catch (Exception e) {
                    LOGGER.warn("Exception while polling AggregateEventEntry, retrying next poll interval", e);
                }

                if (terminated.get()) {
                    break;  // do not call back into a component that is being shut down
                }

                onAppendDetected.run();  // if this throws an exception, terminates the coordination
            }

            LOGGER.info("Exiting " + this);
        });

        pollingThread.start();

        return new Handle() {

            @Override
            public void onEventsAppended(List<TaggedEventMessage<?>> events) {
                if (events.size() > 0) {
                    pollingThread.interrupt();
                }
            }

            @Override
            public void terminate() {
                terminated.set(true);
                pollingThread.interrupt();

                try {
                    if (!pollingThread.join(TERMINATION_TIMEOUT)) {
                        LOGGER.warn(
                            "Polling thread {} did not stop within {}, most likely blocked in a query that does not "
                                + "respond to an interrupt. Leaving it to exit on its own; it is a daemon thread.",
                            pollingThread.getName(), TERMINATION_TIMEOUT
                        );
                    }
                } catch (InterruptedException e) {
                    // Best effort, tried waiting, but got interrupted, restore flag and ignore:
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private long countEvents() {
        try (EntityManager entityManager = entityManagerProvider.getEntityManager()) {
            return entityManager.createQuery(COUNT_EVENTS, Long.class).getSingleResult();
        }
    }
}
