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

package org.axonframework.extension.metrics.dropwizard;

import io.dropwizard.metrics5.Gauge;
import io.dropwizard.metrics5.Metric;
import io.dropwizard.metrics5.MetricName;
import io.dropwizard.metrics5.MetricSet;
import org.axonframework.common.ClockUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.monitoring.MessageMonitor;
import org.axonframework.messaging.monitoring.NoOpMessageMonitorCallback;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link MessageMonitor} implementation dedicated to {@link EventMessage EventMessages}.
 * <p>
 * This monitor defines the latency between the {@link EventMessage#timestamp()} and the {@link Clock#instant()}. Doing
 * so, it depicts the latency from when an event was published compared to when an {@link EventProcessor} processes the
 * event to clarify how far behind an {@code EventProcessor} is.
 * <p>
 * Do note that a replay (as triggered through {@link StreamingEventProcessor#resetTokens()}, for example) will cause
 * this metric to bump up due to the processor handling old events.
 *
 * @author Marijn van Zelst
 * @author Allard Buijze
 * @since 3.0
 */
public class EventProcessorLatencyMonitor implements MessageMonitor<EventMessage>, MetricSet {

    @Deprecated(forRemoval = true, since = "5.2.0")
    private final Clock clock;
    private final AtomicLong processTime = new AtomicLong();

    /**
     * Construct an EventProcessorLatencyMonitor using {@link ClockUtils#get()}.
     */
    public EventProcessorLatencyMonitor() {
        this(ClockUtils.get());
    }

    /**
     * Construct an EventProcessorLatencyMonitor using the given {@code clock}.
     *
     * @param clock defines the {@link Clock} used by this {@link MessageMonitor} implementation
     * @deprecated Use {@link ClockUtils#set(Clock)} if you have to provide a non-default {@link Clock} instance.
     */
    @Deprecated(forRemoval = true, since = "5.2.0")
    public EventProcessorLatencyMonitor(Clock clock) {
        this.clock = clock;
    }

    @Override
    public MonitorCallback onMessageIngested(EventMessage message) {
        //noinspection ConstantConditions
        if (message != null) {
            this.processTime.set(Duration.between(message.timestamp(), clock.instant()).toMillis());
        }
        return NoOpMessageMonitorCallback.INSTANCE;
    }

    @Override
    public Map<MetricName, Metric> getMetrics() {
        Map<MetricName, Metric> metrics = new HashMap<>();
        metrics.put(MetricName.build("latency"), (Gauge<Long>) processTime::get); // NOSONAR
        return metrics;
    }
}
