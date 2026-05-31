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

package org.axonframework.extension.micronaut.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor;

import java.util.Map;

/**
 * Event processor settings.
 * <p>
 * Subclasses are segregating settings for the different processors.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
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
    @Nonnull
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

        @Nonnull
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
        @Nonnull
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
         * Name of the token store of the bean.
         *
         * @return Name of the bean acting as token store for this pooled streaming processor.
         */
        @Nonnull
        String tokenStore();
    }
}
