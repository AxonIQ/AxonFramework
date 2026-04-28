/*
 * Copyright (c) 2010-2024. Axon Framework
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

package org.axonframework.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Properties describing the settings for the default {@link org.axonframework.eventsourcing.eventstore.EmbeddedEventStore}.
 *
 * @author Allard Buijze
 * @since 4.13.1
 */
@ConfigurationProperties("axon.eventstore")
public class EmbeddedEventStoreProperties {

    /**
     * Whether event consumption should be optimized between Event Streams. When enabled, distinct Event Consumers will
     * read events from the same shared stream as soon as they reach the head of the stream, reducing the number of
     * database queries at the cost of additional memory use and background thread activity.
     * <p>
     * Enabled by default. Can be disabled if the application experiences memory pressure during replay or migration,
     * such as when a new TrackingEventProcessor starts replaying a large number of events.
     * <p>
     * Note: this setting can also be configured with the JVM system property {@code optimize-event-consumption}.
     */
    private boolean optimizeEventConsumption = true;

    /**
     * The maximum number of events in the cache shared between the streams of tracking event processors. Only
     * relevant when {@code optimizeEventConsumption} is {@code true}. Defaults to {@code 10000}.
     */
    private int cachedEvents = 10000;

    /**
     * The delay between attempts to fetch new events from the backing storage engine. Defaults to
     * {@code 1000ms}.
     */
    private Duration fetchDelay = Duration.ofMillis(1000);

    /**
     * The delay between cache cleanup attempts. Defaults to {@code 10000ms}.
     */
    private Duration cleanupDelay = Duration.ofMillis(10000);

    /**
     * Returns whether event consumption optimization is enabled.
     *
     * @return {@code true} if optimization is enabled, {@code false} otherwise
     */
    public boolean isOptimizeEventConsumption() {
        return optimizeEventConsumption;
    }

    /**
     * Sets whether event consumption should be optimized between Event Streams.
     *
     * @param optimizeEventConsumption {@code true} to enable optimization, {@code false} to disable it
     */
    public void setOptimizeEventConsumption(boolean optimizeEventConsumption) {
        this.optimizeEventConsumption = optimizeEventConsumption;
    }

    /**
     * Returns the maximum number of events in the shared cache.
     *
     * @return the maximum number of cached events
     */
    public int getCachedEvents() {
        return cachedEvents;
    }

    /**
     * Sets the maximum number of events in the shared cache.
     *
     * @param cachedEvents the maximum number of cached events
     */
    public void setCachedEvents(int cachedEvents) {
        this.cachedEvents = cachedEvents;
    }

    /**
     * Returns the delay between fetch attempts.
     *
     * @return the fetch delay as a {@link Duration}
     */
    public Duration getFetchDelay() {
        return fetchDelay;
    }

    /**
     * Sets the delay between fetch attempts.
     *
     * @param fetchDelay the fetch delay as a {@link Duration}
     */
    public void setFetchDelay(Duration fetchDelay) {
        this.fetchDelay = fetchDelay;
    }

    /**
     * Returns the delay between cache cleanup attempts.
     *
     * @return the cleanup delay as a {@link Duration}
     */
    public Duration getCleanupDelay() {
        return cleanupDelay;
    }

    /**
     * Sets the delay between cache cleanup attempts.
     *
     * @param cleanupDelay the cleanup delay as a {@link Duration}
     */
    public void setCleanupDelay(Duration cleanupDelay) {
        this.cleanupDelay = cleanupDelay;
    }
}
