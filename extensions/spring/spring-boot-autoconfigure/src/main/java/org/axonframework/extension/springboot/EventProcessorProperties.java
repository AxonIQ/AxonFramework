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

package org.axonframework.extension.springboot;

import org.axonframework.common.annotation.Internal;
import org.axonframework.extension.spring.config.EventProcessorSettings;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Properties describing the settings for Event Processors.
 *
 * @author Allard Buijze
 * @since 3.0
 */
@ConfigurationProperties("axon.eventhandling")
public class EventProcessorProperties {

    /**
     * Provides ProcessorSettings per processor name via Environment.
     * <p>
     * <i>This is required due to changes on how spring boot 4 loads configurations properties.
     * If the application configures WebMvc we get cyclic application context. By using {@link Environment} and {@link Binder}
     * to manually create the processors map we avoid this problem. See <a href="https://github.com/AxonFramework/AxonFramework/issues/4010">issue #4010</a> for details.</i>
     *
     * @param environment The spring boot environment.
     * @return The map of {@link ProcessorSettings} per processor name.
     */
    @Internal
    public static Map<String, EventProcessorSettings> getProcessors(Environment environment) {
        Binder binder = Binder.get(environment);

        // Bind the same structure that EventProcessorProperties would expose
        Map<String, ProcessorSettings> raw =
                binder.bind("axon.eventhandling.processors",
                            Bindable.mapOf(String.class, EventProcessorProperties.ProcessorSettings.class))
                      .orElseGet(java.util.HashMap::new);

        return new HashMap<>(raw);
    }

    /**
     * The configuration of each of the processors. The key is the name of the processor, the value represents the
     * settings to use for the processor with that name.
     */
    private final Map<String, ProcessorSettings> processors = new HashMap<>();

    /**
     * Returns the settings for each of the configured processors, by name.
     *
     * @return the settings for each of the configured processors, by name.
     */
    public Map<String, ProcessorSettings> getProcessors() {
        return processors;
    }

    /**
     * The processing modes of an {@link EventProcessor}.
     */
    public enum Mode {
        /**
         * Indicates a {@link SubscribingEventProcessor} should be used.
         */
        SUBSCRIBING,
        /**
         * Indicates a {@link PooledStreamingEventProcessor} should be used.
         */
        POOLED
    }

    /**
     * Processor settings.
     */
    public static class ProcessorSettings
            implements EventProcessorSettings.PooledEventProcessorSettings,
            EventProcessorSettings.SubscribingEventProcessorSettings {

        /**
         * Sets the source for this processor.
         * <p>
         * Defaults to streaming from the {@link org.axonframework.eventsourcing.eventstore.EventStore} when the
         * {@link #mode} is set to {@link Mode#POOLED}, and to subscribing to the {@link EventBus} when the
         * {@link #mode} is set to {@link Mode#SUBSCRIBING}.
         */
        private String source;

        /**
         * Indicates whether this processor should be Pooled, or Subscribing its source. Defaults to
         * {@link Mode#POOLED}.
         */
        private Mode mode = Mode.POOLED;

        /**
         * Indicates the number of segments that should be created when the processor starts for the first time.
         * Defaults to 16 for a {@link PooledStreamingEventProcessor}.
         */
        private int initialSegmentCount = 16;

        /**
         * The interval between attempts to claim tokens by a {@link StreamingEventProcessor}.
         * <p>
         * Defaults to 5000 milliseconds.
         */
        private long tokenClaimInterval = 5000;

        /**
         * The time unit of tokens claim interval.
         * <p>
         * Defaults to {@link TimeUnit#MILLISECONDS}.
         */
        private TimeUnit tokenClaimIntervalTimeUnit = TimeUnit.MILLISECONDS;

        /**
         * The maximum number of threads the processor should process events with. Defaults to 4 for a
         * {@link PooledStreamingEventProcessor}.
         */
        private int threadCount = 4;

        /**
         * The maximum number of events a processor should process as part of a single batch.
         */
        private int batchSize = 1;

        /**
         * Name of the {@link TokenStore} bean used for this processor. Must not be null.
         */
        private String tokenStore = "tokenStore";
        /**
         * The name of the bean that represents the sequencing policy for processing events. If no name is specified,
         * the processor defaults to a {@link SequentialPerAggregatePolicy},
         * which guarantees to process events originating from the same Aggregate instance sequentially, while events
         * from different Aggregate instances may be processed concurrently.
         */
        private String sequencingPolicy;

        /**
         * Returns the name of the bean that should be used as source for Event Messages. If not provided, the
         * {@link EventBus} is used as source.
         *
         * @return the name of the bean that should be used as source for Event Messages.
         */
        @Override
        public String source() {
            return source;
        }

        /**
         * Sets the name of the bean that should be used as source for Event Messages.
         *
         * @param source the name of the bean that should be used as source for Event Messages.
         */
        public void setSource(String source) {
            this.source = source;
        }

        /**
         * Returns the type of processor to configure. Defaults to {@link Mode#POOLED}.
         *
         * @return the type of processor to configure.
         */
        public Mode getMode() {
            return mode;
        }

        /**
         * Retrieves the processor mode.
         *
         * @return pooled-streaming or subscribed mode, falls back to pooled-streaming.
         */
        @Override
        public EventProcessorSettings.ProcessorMode processorMode() {
            if (Mode.SUBSCRIBING.equals(mode)) {
                return ProcessorMode.SUBSCRIBING;
            }
            return ProcessorMode.POOLED;
        }

        /**
         * Sets the type of processor that should be configured. Defaults to {@link Mode#POOLED}.
         *
         * @param mode the type of processor that should be configured.
         */
        public void setMode(Mode mode) {
            this.mode = mode;
        }

        /**
         * Returns the number of initial segments that should be created, if no segments are already present. Defaults
         * to 16 for a {@link PooledStreamingEventProcessor}.
         *
         * @return the number of initial segments that should be created.
         */
        @Override
        public int initialSegmentCount() {
            return initialSegmentCount;
        }

        /**
         * Sets the number of initial segments that should be created, if no segments are already present. Defaults to
         * 16 for a {@link PooledStreamingEventProcessor}.
         *
         * @param initialSegmentCount the number of initial segments that should be created.
         */
        public void setInitialSegmentCount(int initialSegmentCount) {
            this.initialSegmentCount = initialSegmentCount;
        }

        /**
         * Returns the interval between attempts to claim tokens by a {@link StreamingEventProcessor}. Defaults to 5000
         * milliseconds.
         *
         * @return the interval between attempts to claim tokens by a {@link StreamingEventProcessor}.
         */
        public long getTokenClaimInterval() {
            return tokenClaimInterval;
        }

        /**
         * Sets the time to wait after a failed attempt to claim any token, before making another attempt. Defaults to
         * 5000 milliseconds.
         *
         * @param tokenClaimInterval the interval between attempts to claim tokens by a
         *                           {@link StreamingEventProcessor}.
         */
        public void setTokenClaimInterval(long tokenClaimInterval) {
            this.tokenClaimInterval = tokenClaimInterval;
        }

        /**
         * Returns the time unit used to define tokens claim interval. Defaults to {@link TimeUnit#MILLISECONDS}.
         *
         * @return the time unit used to defined tokens claim interval.
         */
        public TimeUnit getTokenClaimIntervalTimeUnit() {
            return tokenClaimIntervalTimeUnit;
        }

        @Override
        public long tokenClaimIntervalInMillis() {
            return tokenClaimIntervalTimeUnit.toMillis(tokenClaimInterval);
        }

        /**
         * Sets the time unit used to defined tokens claim interval. It must be a valid value of {@link TimeUnit}.
         * Defaults to {@link TimeUnit#MILLISECONDS}.
         *
         * @param tokenClaimIntervalTimeUnit the time unit used to defined tokens claim interval.
         */
        public void setTokenClaimIntervalTimeUnit(TimeUnit tokenClaimIntervalTimeUnit) {
            this.tokenClaimIntervalTimeUnit = tokenClaimIntervalTimeUnit;
        }

        /**
         * Returns the number of threads to use to process Events, when using a {@link StreamingEventProcessor}
         * implementation. Defaults to the configured number of initial segments. If this field is not configured, the
         * thread count defaults to 4 for a {@link PooledStreamingEventProcessor}.
         *
         * @return the number of threads to use to process Events.
         */
        @Override
        public int threadCount() {
            int defaultThreadCount = 1;
            return threadCount < 1 ? defaultThreadCount : threadCount;
        }

        /**
         * Sets the number of threads to use to process Events. If this field is not configured, the thread count
         * defaults to 4 for a {@link PooledStreamingEventProcessor}.
         * <p>
         *
         * @param threadCount the number of threads to use to process Events.
         */
        public void setThreadCount(int threadCount) {
            this.threadCount = threadCount;
        }

        /**
         * Returns the maximum size of a processing batch. This is the number of events that a processor in "tracking"
         * and "pooled" mode will attempt to read and process within a single Unit of Work / Transaction.
         *
         * @return the maximum size of a processing batch.
         */
        @Override
        public int batchSize() {
            int defaultBatchSize = 1;
            return batchSize < 1 ? defaultBatchSize : batchSize;
        }

        /**
         * Sets the maximum size of a processing batch. This is the number of events that a processor in "tracking" and
         * "pooled" mode will attempt to read and process within a single Unit of Work / Transaction. Defaults to 1.
         *
         * @param batchSize the maximum size of a processing batch.
         */
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        /**
         * Sets the name of the TokenStore bean.
         *
         * @param tokenStore A name of the Spring Bean used for this processor.
         */
        public void setTokenStore(String tokenStore) {
            Objects.requireNonNull(tokenStore, "TokenStore cannot be null");
            this.tokenStore = tokenStore;
        }

        /**
         * Retrieves the name of the TokenStore bean.
         *
         * @return Name of the token store Spring Bean.
         */
        @Override
                public String tokenStore() {
            return tokenStore;
        }

        /**
         * Returns the name of the bean that defines the
         * {@link SequencingPolicy} for this processor.
         *
         * @return the name of the bean that defines the
         * {@link SequencingPolicy} for this processor.
         */
        public String sequencingPolicy() {
            return sequencingPolicy;
        }

        /**
         * Sets the name of the bean that defines the
         * {@link SequencingPolicy} for this processor. The
         * {@code SequencingPolicy} describes which Events must be handled sequentially, and which can be handled
         * concurrently. Defaults to a {@link SequentialPerAggregatePolicy}.
         *
         * @param sequencingPolicy the name of the bean that defines the
         *                         {@link SequencingPolicy} for this
         *                         processor.
         */
        public void setSequencingPolicy(String sequencingPolicy) {
            this.sequencingPolicy = sequencingPolicy;
        }

    }
}
