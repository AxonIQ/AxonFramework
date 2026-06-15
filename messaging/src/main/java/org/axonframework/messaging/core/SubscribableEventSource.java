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

package org.axonframework.messaging.core;

import org.axonframework.common.Registration;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Interface for a source of {@link EventMessage EventMessages} to which event processors can subscribe.
 * <p>
 * Provides functionality to {@link #subscribe(BiFunction) subscribe} event batch consumers to receive
 * {@link EventMessage events} published to this source. When subscribed, consumers will receive all events published to
 * this source since the subscription.
 * <p>
 * This interface is the replacement for the deprecated {@link SubscribableEventSource},
 * focusing specifically on event message handling.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface SubscribableEventSource {

    /**
     * Subscribe the given {@code eventsBatchConsumer} to this event source. When subscribed, it will receive all events
     * published to this source since the subscription.
     * <p>
     * If the given {@code eventsBatchConsumer} is already subscribed, nothing happens.
     * <p>
     * <b>Note on {@link ProcessingContext}:</b> The {@link ProcessingContext} parameter passed to the consumer may be
     * {@code null}. When {@code null}, it is the responsibility of the registered {@code eventsBatchConsumer} to create
     * an appropriate {@link ProcessingContext} as needed for processing the events.
     *
     * @param eventsBatchConsumer The event batches consumer to subscribe.
     * @return A {@link Registration} handle to unsubscribe the {@code eventsBatchConsumer}. When unsubscribed, it will
     * no longer receive events.
     */
    Registration subscribe(BiFunction<List<? extends EventMessage>, @Nullable ProcessingContext, CompletableFuture<?>> eventsBatchConsumer);
}
