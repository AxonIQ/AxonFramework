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

package org.axonframework.extension.spring.config;

import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Event processor settings.
 * <p>
 * Subclasses are segregating settings for the different processors.
 *
 * @author Simon Zambrovski
 * @since 5.0.0
 */
public sealed interface EventProcessorSettings {

    /**
     * Key for default settings. Intentionally contains <code>..</code> to avoid potential package name clashing.
     */
    String DEFAULT = "..default";

    /**
     * Holder class to be able to retrieve a map of those by a single non-parameterized class.
     *
     * @param settings setting to wrap.
     */
    record MapWrapper(Map<String, EventProcessorSettings> settings) {

    }

    /**
     * The processing modes of an {@link EventProcessor}.
     */
    enum ProcessorMode {
        /**
         * Indicates a {@link SubscribingEventProcessor} should
         * be used.
         */
        SUBSCRIBING,
        /**
         * Indicates a {@link PooledStreamingEventProcessor} should be used.
         */
        POOLED
    }

    /**
     * Retrieves the processor mode.
     *
     * @return processor mode.
     */
    ProcessorMode processorMode();

    /**
     * Name of the bean acting as source for this processor.
     *
     * @return only used if non-null.
     */
    @Nullable
    String source();

    /**
     * Settings for subscribing event processor.
     */
    non-sealed interface SubscribingEventProcessorSettings extends EventProcessorSettings {

        @Override
        default ProcessorMode processorMode() {
            return ProcessorMode.SUBSCRIBING;
        }
    }

    /**
     * Settings for pooled event processor.
     */
    non-sealed interface PooledEventProcessorSettings extends EventProcessorSettings {

        /**
         * Retrieves the processor mode.
         *
         * @return processor mode.
         */
                default ProcessorMode processorMode() {
            return ProcessorMode.POOLED;
        }

        /**
         * Initial segment count.
         *
         * @return returns initial segment count.
         */
        int initialSegmentCount();

        /**
         * Retrieves token claim interval.
         *
         * @return interval in milliseconds.
         */
        long tokenClaimIntervalInMillis();

        /**
         * Thread count for pooled processor.
         *
         * @return a positive integer describing the size of the thread pool.
         */
        int threadCount();

        /**
         * Batch size for the pooled processor.
         *
         * @return a positive integer describing the size of the batch.
         */
        int batchSize();

        /**
         * Threshold, in milliseconds, after which a work package extends the claim on its {@code TrackingToken} in the
         * absence of event handling.
         * <p>
         * Defaults to 5000 milliseconds, matching the default of the pooled streaming processor configuration.
         *
         * @return the claim extension threshold in milliseconds
         */
        default long claimExtensionThresholdInMillis() {
            return 5000;
        }

        /**
         * Whether the coordinator extends the claims of its work packages, rather than leaving that to the work
         * packages themselves.
         * <p>
         * Enable this when event handling regularly takes longer than the claim timeout of the {@code TokenStore}: a
         * work package cannot extend its own claim while it is handling a batch, nor while it waits for a thread of
         * the worker executor that lengthy handling on other segments occupies. Defaults to {@code false}.
         *
         * @return {@code true} if the coordinator extends the claims of its work packages, {@code false} otherwise
         */
        default boolean coordinatorClaimExtension() {
            return false;
        }

        /**
         * Name of the bean acting as token store for this pooled streaming processor.
         *
         * @return only used if non-null.
         */
        @Nullable
        String tokenStore();
    }
}
