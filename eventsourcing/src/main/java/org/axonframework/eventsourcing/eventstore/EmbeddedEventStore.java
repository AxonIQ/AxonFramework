/*
 * Copyright (c) 2010-2023. Axon Framework
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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.io.IOUtils;
import org.axonframework.eventhandling.EventBusSpanFactory;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingEventStream;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.lifecycle.Lifecycle;
import org.axonframework.lifecycle.Phase;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.axonframework.tracing.SpanFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

import static java.util.stream.Collectors.toList;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.BuilderUtils.assertPositive;

/**
 * Implementation of an {@link EventStore} that stores and fetches events using an {@link EventStorageEngine}. If
 * supported by its storage engine the embedded event store provides event tracking and replaying capabilities.
 * <p>
 * The event store can be tracked by multiple event processors simultaneously. To prevent that each event processor
 * needs to read from the storage engine individually the embedded event store contains a cache of the most recent
 * events. This cache is shared between the streams of various event processors. So, assuming an event processor
 * processes events fast enough and is not far behind the head of the event log it will not need a private connection to
 * the underlying data store. The size of the cache (in number of events) is configurable. This 'event consumption
 * optimization' might in some scenarios not be desirable, as it will spin up additional threads and perform some
 * locking operations. Hence it is switchable by using the {@link Builder#optimizeEventConsumption(boolean)} upon
 * creation. Additionally, this can also be turned off by providing a system property with key {@code
 * optimize-event-consumption}.
 * <p>
 * The embedded event store automatically fetches new events from the store if there is at least one registered tracking
 * event processor present. It will do so after new events are committed to the store, as well as periodically as events
 * may have been committed by other nodes or applications. This periodic fetch delay is configurable.
 *
 * @author Rene de Waele
 * @since 3.0
 */
public class EmbeddedEventStore extends AbstractEventStore implements Lifecycle {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedEventStore.class);

    private static final ThreadGroup THREAD_GROUP = new ThreadGroup(EmbeddedEventStore.class.getSimpleName());
    private static final String OPTIMIZE_EVENT_CONSUMPTION_SYSTEM_PROPERTY = "optimize-event-consumption";

    private final Lock consumerLock = new ReentrantLock();
    private final Condition consumableEventsCondition = consumerLock.newCondition();
    private final Set<EventConsumer> tailingConsumers = new CopyOnWriteArraySet<>();
    private final EventProducer producer;
    private final long cleanupDelayMillis;
    private final ThreadFactory threadFactory;
    private final boolean optimizeEventConsumption;
    private final ScheduledExecutorService cleanupService;
    private final AtomicBoolean producerStarted = new AtomicBoolean();
    private volatile Node oldest;

    /**
     * Instantiate a {@link EmbeddedEventStore} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link EventStorageEngine} is not {@code null}, and will throw an {@link
     * AxonConfigurationException} if it is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link EmbeddedEventStore} instance
     */
    protected EmbeddedEventStore(Builder builder) {
        super(builder);
        this.threadFactory = builder.threadFactory;
        this.optimizeEventConsumption = builder.optimizeEventConsumption;
        cleanupService = Executors.newScheduledThreadPool(1, this.threadFactory);
        TimeUnit timeUnit = builder.timeUnit;
        producer = new EventProducer(timeUnit.toNanos(builder.fetchDelay), builder.cachedEvents);
        cleanupDelayMillis = timeUnit.toMillis(builder.cleanupDelay);
    }

    /**
     * Instantiate a Builder to be able to create an {@link EmbeddedEventStore}.
     * <p>
     * The following configurable fields have defaults:
     * <ul>
     * <li>The {@link MessageMonitor} is defaulted to a {@link NoOpMessageMonitor}.</li>
     * <li>The {@link EventBusSpanFactory} is defaulted to a {@link org.axonframework.eventhandling.DefaultEventBusSpanFactory} backed by a {@link org.axonframework.tracing.NoOpSpanFactory}.</li>
     * <li>The {@code cachedEvents} is defaulted to {@code 10000}.</li>
     * <li>The {@code fetchDelay} is defaulted to {@code 1000}.</li>
     * <li>The {@code cleanupDelay} is defaulted to {@code 10000}.</li>
     * <li>The {@link TimeUnit} is defaulted to {@link TimeUnit#MILLISECONDS}.</li>
     * <li>The {@link ThreadFactory} is defaulted to {@link AxonThreadFactory} with {@link ThreadGroup} {@link
     * EmbeddedEventStore#THREAD_GROUP}.</li>
     * <li>The {@code optimizeEventConsumption} is defaulted to {@code true}.</li>
     * </ul>
     * The {@link EventStorageEngine} is a <b>hard requirement</b> and as such should be provided.
     *
     * @return a Builder to be able to create a {@link EmbeddedEventStore}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Method to invoke when the application shuts down. This closes all event streams used for event store tracking.
     */
    public void shutDown() {
        tailingConsumers.forEach(IOUtils::closeQuietly);
        IOUtils.closeQuietly(producer);
        cleanupService.shutdownNow();
    }

    private void ensureProducerStarted() {
        if (producerStarted.compareAndSet(false, true)) {
            threadFactory.newThread(() -> {
                try {
                    producer.run();
                } catch (InterruptedException e) {
                    logger.warn("Producer thread was interrupted. Shutting down event store.", e);
                    Thread.currentThread().interrupt();
                }
            }).start();
            cleanupService.scheduleWithFixedDelay(new Cleaner(), cleanupDelayMillis, cleanupDelayMillis,
                                                  TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void afterCommit(List<? extends EventMessage<?>> events) {
        producer.fetchIfWaiting();
    }

    @Override
    public TrackingEventStream openStream(TrackingToken trackingToken) {
        Node node = findNode(trackingToken);
        EventConsumer eventConsumer;
        if (node != null && optimizeEventConsumption) {
            eventConsumer = new EventConsumer(node);
            tailingConsumers.add(eventConsumer);
        } else {
            eventConsumer = new EventConsumer(trackingToken);
        }
        return eventConsumer;
    }

    private Node findNode(TrackingToken trackingToken) {
        Node node = oldest;
        while (node != null && !node.event.trackingToken().equals(trackingToken)) {
            node = node.next;
        }
        return node;
    }

    @Override
    public void registerLifecycleHandlers(@Nonnull LifecycleRegistry handle) {
        handle.onShutdown(Phase.INBOUND_EVENT_CONNECTORS - 10, this::shutDown);
    }

    private static class Node {

        private final long index;
        private final TrackingToken previousToken;
        private final TrackedEventMessage<?> event;
        private volatile Node next;

        private Node(long index, TrackingToken previousToken, TrackedEventMessage<?> event) {
            this.index = index;
            this.previousToken = previousToken;
            this.event = event;
        }
    }

    private class EventProducer implements AutoCloseable {

        private final Lock lock = new ReentrantLock();
        private final Condition dataAvailableCondition = lock.newCondition();
        private final long fetchDelayNanos;
        private final int cachedEvents;
        private volatile boolean shouldFetch;
        private volatile boolean closed;
        private Stream<? extends TrackedEventMessage<?>> eventStream;
        private Node newest;

        private EventProducer(long fetchDelayNanos, int cachedEvents) {
            this.fetchDelayNanos = fetchDelayNanos;
            this.cachedEvents = cachedEvents;
        }

        private void run() throws InterruptedException {
            boolean dataFound = false;

            while (!closed) {
                shouldFetch = true;
                while (shouldFetch) {
                    shouldFetch = false;
                    dataFound = fetchData();
                }
                if (!dataFound) {
                    waitForData();
                }
            }
        }

        private void waitForData() throws InterruptedException {
            lock.lock();
            try {
                if (!shouldFetch) {
                    dataAvailableCondition.awaitNanos(fetchDelayNanos);
                }
            } finally {
                lock.unlock();
            }
        }

        private void fetchIfWaiting() {
            shouldFetch = true;
            lock.lock();
            try {
                dataAvailableCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private boolean fetchData() {
            Node currentNewest = newest;
            if (!tailingConsumers.isEmpty()) {
                try {
                    eventStream = storageEngine().readEvents(lastToken(), true);
                    eventStream.forEach(event -> {
                        Node node = new Node(nextIndex(), lastToken(), event);
                        if (newest != null) {
                            newest.next = node;
                        }
                        newest = node;
                        if (oldest == null) {
                            oldest = node;
                        }
                        notifyConsumers();
                        trimCache();
                    });
                } catch (Exception e) {
                    logger.error("Failed to read events from the underlying event storage", e);
                }
            }
            return !Objects.equals(newest, currentNewest);
        }

        private TrackingToken lastToken() {
            if (newest == null) {
                List<TrackingToken> tokens = tailingConsumers.stream().map(EventConsumer::lastToken).collect(toList());
                return tokens.isEmpty() || tokens.contains(null) ? null : tokens.get(0);
            } else {
                return newest.event.trackingToken();
            }
        }

        private long nextIndex() {
            return newest == null ? 0 : newest.index + 1;
        }

        private void notifyConsumers() {
            consumerLock.lock();
            try {
                consumableEventsCondition.signalAll();
            } finally {
                consumerLock.unlock();
            }
        }

        private void trimCache() {
            Node last = oldest;
            while (newest != null && last != null && newest.index - last.index >= cachedEvents) {
                last = last.next;
            }
            oldest = last;
        }

        @Override
        public void close() {
            closed = true;
            if (eventStream != null) {
                eventStream.close();
            }
        }
    }

    private class EventConsumer implements TrackingEventStream {

        private Stream<? extends TrackedEventMessage<?>> privateStream;
        private Iterator<? extends TrackedEventMessage<?>> privateIterator;
        private volatile TrackingToken lastToken;
        private volatile Node lastNode;
        private TrackedEventMessage<?> peekedEvent;

        private EventConsumer(Node lastNode) {
            this(lastNode.event.trackingToken());
            this.lastNode = lastNode;
        }

        private EventConsumer(TrackingToken startToken) {
            this.lastToken = startToken;
        }

        @Override
        public Optional<TrackedEventMessage<?>> peek() {
            return Optional.ofNullable(peekedEvent == null && !hasNextAvailable() ? null : peekedEvent);
        }

        @Override
        public boolean hasNextAvailable(int timeout, TimeUnit unit) throws InterruptedException {
            return peekedEvent != null || (peekedEvent = peek(timeout, unit)) != null;
        }

        @Override
        public TrackedEventMessage<?> nextAvailable() throws InterruptedException {
            while (peekedEvent == null) {
                peekedEvent = peek(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
            TrackedEventMessage<?> result = peekedEvent;
            peekedEvent = null;
            return result;
        }

        private TrackedEventMessage<?> peek(int timeout, TimeUnit timeUnit) throws InterruptedException {
            boolean allowSwitchToTailingConsumer = optimizeEventConsumption;
            if (tailingConsumers.contains(this)) {
                if (!behindGlobalCache()) {
                    return peekGlobalStream(timeout, timeUnit);
                }
                stopTailingGlobalStream();
                // we want to prevent switching back immediately, as it may produce a StackOverflowException
                allowSwitchToTailingConsumer = false;
            }
            return peekPrivateStream(allowSwitchToTailingConsumer, timeout, timeUnit);
        }

        private boolean behindGlobalCache() {
            return oldest != null && (this.lastNode != null ? this.lastNode.index < oldest.index : nextNode() == null);
        }

        private void stopTailingGlobalStream() {
            tailingConsumers.remove(this);
            this.lastNode = null; //makes old nodes garbage collectible
        }

        private TrackedEventMessage<?> peekGlobalStream(int timeout, TimeUnit timeUnit) throws InterruptedException {
            Node nextNode;
            if ((nextNode = nextNode()) == null && timeout > 0) {
                consumerLock.lock();
                try {
                    if (consumableEventsCondition.await(timeout, timeUnit)) {
                        nextNode = nextNode();
                    }
                } finally {
                    consumerLock.unlock();
                }
            }
            if (nextNode != null) {
                if (tailingConsumers.contains(this)) {
                    lastNode = nextNode;
                }
                lastToken = nextNode.event.trackingToken();
                return nextNode.event;
            } else {
                return null;
            }
        }

        private TrackedEventMessage<?> peekPrivateStream(boolean allowSwitchToTailingConsumer,
                                                         int timeout,
                                                         TimeUnit timeUnit) throws InterruptedException {
            if (privateIterator == null) {
                privateStream = storageEngine().readEvents(lastToken, false);
                privateIterator = privateStream.iterator();
            }
            if (privateIterator.hasNext()) {
                TrackedEventMessage<?> nextEvent = privateIterator.next();
                lastToken = nextEvent.trackingToken();
                return nextEvent;
            } else if (allowSwitchToTailingConsumer) {
                closePrivateStream();
                lastNode = findNode(lastToken);
                tailingConsumers.add(this);
                ensureProducerStarted();
                return timeout > 0 ? peek(timeout, timeUnit) : null;
            } else {
                consumerLock.lock();
                try {
                    if (consumableEventsCondition.await(timeout, timeUnit) && privateIterator.hasNext()) {
                        TrackedEventMessage<?> nextEvent = privateIterator.next();
                        lastToken = nextEvent.trackingToken();
                        return nextEvent;
                    }
                    return null;
                } finally {
                    consumerLock.unlock();
                }
            }
        }

        private Node nextNode() {
            Node node = lastNode;
            if (node != null) {
                return node.next;
            }
            node = oldest;
            while (node != null && !Objects.equals(node.previousToken, lastToken)) {
                node = node.next;
            }
            return node;
        }

        private TrackingToken lastToken() {
            return lastToken;
        }

        @Override
        public void close() {
            closePrivateStream();
            stopTailingGlobalStream();
        }

        private void closePrivateStream() {
            Optional.ofNullable(privateStream).ifPresent(stream -> {
                privateStream = null;
                privateIterator = null;
                stream.close();
            });
        }
    }

    private class Cleaner implements Runnable {

        @Override
        public void run() {
            Node oldestCachedNode = oldest;
            if (oldestCachedNode == null || oldestCachedNode.previousToken == null) {
                return;
            }
            tailingConsumers.stream().filter(EventConsumer::behindGlobalCache).forEach(consumer -> {
                logger.debug("An event stream cannot read from the local cache. It either runs behind, or its current token cannot be found in the cache. Opening a dedicated stream.");
                consumer.stopTailingGlobalStream();
            });
        }
    }

    /**
     * Builder class to instantiate an {@link EmbeddedEventStore}.
     * <p>
     * The following configurable fields have defaults:
     * <ul>
     * <li>The {@link MessageMonitor} is defaulted to a {@link NoOpMessageMonitor}.</li>
     * <li>The {@link EventBusSpanFactory} is defaulted to a {@link org.axonframework.eventhandling.DefaultEventBusSpanFactory} backed by a {@link org.axonframework.tracing.NoOpSpanFactory}.</li>
     * <li>The {@code cachedEvents} is defaulted to {@code 10000}.</li>
     * <li>The {@code fetchDelay} is defaulted to {@code 1000}.</li>
     * <li>The {@code cleanupDelay} is defaulted to {@code 10000}.</li>
     * <li>The {@link TimeUnit} is defaulted to {@link TimeUnit#MILLISECONDS}.</li>
     * <li>The {@link ThreadFactory} is defaulted to {@link AxonThreadFactory} with {@link ThreadGroup} {@link
     * EmbeddedEventStore#THREAD_GROUP}.</li>
     * <li>The {@code optimizeEventConsumption} is defaulted to {@code true}.</li>
     * </ul>
     * The {@link EventStorageEngine} is a <b>hard requirement</b> and as such should be provided.
     */
    public static class Builder extends AbstractEventStore.Builder {

        private int cachedEvents = 10000;
        private long fetchDelay = 1000L;
        private long cleanupDelay = 10000L;
        private TimeUnit timeUnit = TimeUnit.MILLISECONDS;
        private ThreadFactory threadFactory = new AxonThreadFactory(THREAD_GROUP);
        private boolean optimizeEventConsumption = fetchEventConsumptionSystemPropertyOrDefault();

        // Default to optimize event consumption if no property has been set
        private static boolean fetchEventConsumptionSystemPropertyOrDefault() {
            String optimizeEventConsumptionSystemProperty = System.getProperty(
                    OPTIMIZE_EVENT_CONSUMPTION_SYSTEM_PROPERTY);
            return optimizeEventConsumptionSystemProperty == null ||
                    Boolean.TRUE.toString().equalsIgnoreCase(optimizeEventConsumptionSystemProperty);
        }

        @Override
        public Builder storageEngine(EventStorageEngine storageEngine) {
            super.storageEngine(storageEngine);
            return this;
        }

        @Override
        public Builder messageMonitor(@Nonnull MessageMonitor<? super EventMessage<?>> messageMonitor) {
            super.messageMonitor(messageMonitor);
            return this;
        }

        @Override
        public Builder spanFactory(@Nonnull SpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        @Override
        public Builder spanFactory(@Nonnull EventBusSpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        /**
         * Sets the maximum number of events in the cache that is shared between the streams of tracking event
         * processors. Defaults to {@code 10000}.
         *
         * @param cachedEvents an {@code int} specifying the maximum number of events in the cache that is shared
         *                     between the streams of tracking event processors
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder cachedEvents(int cachedEvents) {
            assertPositive(cachedEvents, "The cached events count should be a positive number");
            this.cachedEvents = cachedEvents;
            return this;
        }

        /**
         * Sets the time to wait before fetching new events from the backing storage engine while tracking after a
         * previous stream was fetched and read. Note that this only applies to situations in which no events from the
         * current application have meanwhile been committed. If the current application commits events then those
         * events are fetched without delay.
         * <p>
         * Defaults to {@code 1000}. Together with the {@link Builder#timeUnit}, this will define the exact fetch
         * delay.
         *
         * @param fetchDelay a {@code long} specifying the time to wait before fetching new events from the backing
         *                   storage engine while tracking after a previous stream was fetched and read
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder fetchDelay(long fetchDelay) {
            assertPositive(fetchDelay, "The fetch delay should be a positive number");
            this.fetchDelay = fetchDelay;
            return this;
        }

        /**
         * Sets the delay between two clean ups of lagging event processors. An event processor is lagging behind and
         * removed from the set of processors that track cached events if the oldest event in the cache is newer than
         * the last processed event of the event processor. Once removed the processor will be independently fetching
         * directly from the event storage engine until it has caught up again. Event processors will not notice this
         * change during tracking (i.e. the stream is not closed when an event processor falls behind and is removed).
         * <p>
         * Defaults to {@code 1000}. Together with the {@link Builder#timeUnit}, this will define the exact clean up
         * delay.
         *
         * @param cleanupDelay a {@code long} specifying the delay between two clean ups of lagging event processors
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder cleanupDelay(long cleanupDelay) {
            assertPositive(cleanupDelay, "The clean-up delay should be a positive number");
            this.cleanupDelay = cleanupDelay;
            return this;
        }

        /**
         * Sets the {@link TimeUnit} for the {@link Builder#fetchDelay} and {@link Builder#cleanupDelay}. Defaults to
         * {@link TimeUnit#MILLISECONDS}.
         *
         * @param timeUnit the {@link TimeUnit} for the {@link Builder#fetchDelay} and {@link Builder#cleanupDelay}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder timeUnit(TimeUnit timeUnit) {
            assertNonNull(timeUnit, "TimeUnit may not be null");
            this.timeUnit = timeUnit;
            return this;
        }

        /**
         * Sets the {@link ThreadFactory} used to create threads for consuming, producing and cleaning up. Defaults to a
         * {@link AxonThreadFactory} with {@link ThreadGroup} {@link EmbeddedEventStore#THREAD_GROUP}.
         *
         * @param threadFactory a {@link ThreadFactory} used to create threads for consuming, producing and cleaning up
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder threadFactory(ThreadFactory threadFactory) {
            assertNonNull(threadFactory, "ThreadFactory may not be null");
            this.threadFactory = threadFactory;
            return this;
        }

        /**
         * Sets whether event consumption should be optimized between Event Stream. If set to {@code true}, distinct
         * Event Consumers will read events from the same stream as soon as they reach the head of the stream. If {@code
         * false}, they will stay on a private stream. The latter means more database resources will be used, but no
         * side threads are created to fill the consumer cache nor locking is done on consumer threads. This field can
         * also be configured by providing a system property with key {@code optimize-event-consumption}. Defaults to
         * {@code true}.
         *
         * @param optimizeEventConsumption a {@code boolean} defining whether to optimize event consumption of threads
         *                                 by introducing a Event Cache Production thread tailing the head of the stream
         *                                 for the consumers
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder optimizeEventConsumption(boolean optimizeEventConsumption) {
            this.optimizeEventConsumption = optimizeEventConsumption;
            return this;
        }

        /**
         * Initializes a {@link EmbeddedEventStore} as specified through this Builder.
         *
         * @return a {@link EmbeddedEventStore} as specified through this Builder
         */
        public EmbeddedEventStore build() {
            return new EmbeddedEventStore(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        @Override
        protected void validate() throws AxonConfigurationException {
            super.validate();
        }
    }
}
