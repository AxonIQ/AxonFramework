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

package org.axonframework.extension.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Properties describing configuration of Aggregate-based JPA Event Storage engine.
 *
 * @since 5.1.0
 * @author Daniel Karapishchenko
 */
@ConfigurationProperties("axon.eventstorage.jpa")
public class JpaEventStorageEngineConfigurationProperties {

    private final int batchSize;
    private final int gapCleaningThreshold;
    private final int gapTimeout;
    private final long lowestGlobalSequence;
    private final int maxGapOffset;
    private final long pollingInterval;

    @ConstructorBinding
    public JpaEventStorageEngineConfigurationProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("250") int gapCleaningThreshold,
        @DefaultValue("10000") int gapTimeout,
        @DefaultValue("1") long lowestGlobalSequence,
        @DefaultValue("60000") int maxGapOffset,
        @DefaultValue("1000") long pollingInterval
    ) {
        this.batchSize = batchSize;
        this.gapCleaningThreshold = gapCleaningThreshold;
        this.gapTimeout = gapTimeout;
        this.lowestGlobalSequence = lowestGlobalSequence;
        this.maxGapOffset = maxGapOffset;
        this.pollingInterval = pollingInterval;
    }

    /**
     * Batch size to retrieve events from the storage.
     *
     * @return The batch size to retrieve events from the storage.
     */
    public int batchSize() {
        return batchSize;
    }

    /**
     * Retrieves the threshold for number of gaps in a gap-aware token before a cleanup takes place.
     *
     * @return The threshold for number of gaps in a gap-aware token before a cleanup takes place.
     */
    public int gapCleaningThreshold() {
        return gapCleaningThreshold;
    }

    /**
     * Retrieves the time until a gap in global index is considered as timed out.
     *
     * @return The time until a gap in global index is considered as timed out.
     */
    public int gapTimeout() {
        return gapTimeout;
    }

    /**
     * Retrieves the minimum value for the global index auto generation.
     *
     * @return The minimum value for the global index auto generation.
     */
    public long lowestGlobalSequence() {
        return lowestGlobalSequence;
    }

    /**
     * Retrieves the maximum distance in sequence numbers between a gap and the event with the highest known index.
     *
     * @return The maximum distance in sequence numbers between a gap and the event with the highest known index.
     */
    public int maxGapOffset() {
        return maxGapOffset;
    }

    /**
     * Retries the polling interval in milliseconds to detect new appended events.
     *
     * @return The polling interval. Defaults to 1000 ms.
     */
    public long pollingInterval() {
        return pollingInterval;
    }
}


