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

package org.axonframework.messaging.eventhandling;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.Registration;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiFunction;

/**
 * Internal utility class for managing event subscribers.
 * <p>
 * This class provides thread-safe subscription management and notification of subscribers when events are published. It
 * is designed to be reused across different components in the framework that need to support event subscription.
 * <p>
 * The subscription mechanism is thread-safe, using {@link CopyOnWriteArraySet} to allow concurrent modifications and
 * iterations. Duplicate subscriptions are automatically prevented.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
class EventSubscribers implements DescribableComponent {

    private static final Logger logger = LoggerFactory.getLogger(EventSubscribers.class);

    private final Set<BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>>> subscribers = new CopyOnWriteArraySet<>();

    /**
     * Subscribes the given {@code eventsBatchConsumer} to receive notifications when events are published.
     * <p>
     * If the consumer is already subscribed, it will not be added again and an info message will be logged.
     *
     * @param eventsBatchConsumer The consumer to subscribe for event notifications.
     * @return A {@link Registration} that can be used to unsubscribe the consumer.
     */
    public Registration subscribe(
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> eventsBatchConsumer
    ) {
        if (this.subscribers.add(eventsBatchConsumer)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Event subscriber [{}] subscribed successfully", eventsBatchConsumer);
            }
        } else {
            logger.info("Event subscriber [{}] not added. It was already subscribed", eventsBatchConsumer);
        }
        return () -> {
            if (subscribers.remove(eventsBatchConsumer)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Event subscriber {} unsubscribed successfully", eventsBatchConsumer);
                }
                return true;
            } else {
                logger.info("Event subscriber {} not removed. It was already unsubscribed", eventsBatchConsumer);
                return false;
            }
        };
    }

    /**
     * Notifies all subscribers with the given events and processing context.
     * <p>
     * All subscriber futures are executed in parallel and the returned future completes when all of them complete.
     * <p>
     * A failing subscriber never stops the remaining subscribers from being notified. Whether it returns a failed
     * future or throws, its failure is reported through the returned future.
     *
     * @param events  The list of events to notify subscribers about.
     * @param context The {@link ProcessingContext} associated with the events, may be {@code null}.
     * @return A {@link CompletableFuture} that completes when all subscriber futures have completed.
     */
    public CompletableFuture<Void> notifySubscribers(
            List<? extends EventMessage> events,
            @Nullable ProcessingContext context
    ) {
        var consumeFutures = subscribers.stream()
                                        .map(subscriber -> notify(subscriber, events, context))
                                        .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(consumeFutures);
    }

    private static CompletableFuture<?> notify(
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> subscriber,
            List<? extends EventMessage> events,
            @Nullable ProcessingContext context
    ) {
        try {
            return subscriber.apply(events, context);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("subscribers", subscribers);
    }
}