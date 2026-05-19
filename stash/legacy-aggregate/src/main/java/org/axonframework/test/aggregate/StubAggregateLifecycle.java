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

package org.axonframework.test.aggregate;

import org.axonframework.common.IdentifierFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.modelling.command.Aggregate;
import org.axonframework.modelling.command.AggregateLifecycle;
import org.axonframework.modelling.command.ApplyMore;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Stub implementation of an AggregateLifecycle that registers all applied events for verification later. This lifecycle
 * instance can be activated (see {@link #activate()}) and deactivated (see {@link #close()}) at will. Events applied
 * while it is active are stored and can be retrieved using {@link #getAppliedEvents()} or
 * {@link #getAppliedEventPayloads()}.
 */
public class StubAggregateLifecycle extends AggregateLifecycle {

    private static final String AGGREGATE_TYPE = "stubAggregate";

    private Runnable registration;
    private final List<EventMessage> appliedMessages = new CopyOnWriteArrayList<>();
    private boolean deleted;

    /**
     * Activates this lifecycle instance. Any invocations to static AggregateLifecycle methods will use this instance
     * until {@link #close()} is called.
     */
    public void activate() {
        super.startScope();
        this.registration = super::endScope;
    }

    /**
     * Closes this lifecycle instance, restoring to the situation prior to this lifecycle being started. If any
     * lifecycle instance was active before this one started, it will be reactivated.
     */
    public void close() {
        if (registration != null) {
            registration.run();
        }
        registration = null;
    }

    @Override
    protected boolean getIsLive() {
        return true;
    }

    @Override
    protected <T> Aggregate<T> doCreateNew(Class<T> aggregateType, Callable<T> factoryMethod) throws Exception {
        return null;
    }

    @Override
    protected String type() {
        return AGGREGATE_TYPE;
    }

    @Override
    protected Object identifier() {
        return IdentifierFactory.getInstance().generateIdentifier();
    }

    @Override
    protected Long version() {
        return 0L;
    }

    @Override
    protected void doMarkDeleted() {
        this.deleted = true;
    }

    @SuppressWarnings("unchecked")
    private static <P> EventMessage asEventMessage(Object event) {
        if (event instanceof EventMessage) {
            return (EventMessage) event;
        } else if (event instanceof Message message) {
            return new GenericEventMessage(message);
        }
        return new GenericEventMessage(
                new GenericMessage(new MessageType(event.getClass()), event)
        );
    }

    @Override
    protected <T> ApplyMore doApply(T payload, Metadata metadata) {
        appliedMessages.add(new GenericEventMessage(new MessageType(payload.getClass()), payload, metadata));

        return new ApplyMore() {
            @Override
            public ApplyMore andThenApply(Supplier<?> payloadOrMessageSupplier) {
                appliedMessages.add(asEventMessage(payloadOrMessageSupplier.get()));
                return this;
            }

            @Override
            public ApplyMore andThen(Runnable runnable) {
                runnable.run();
                return this;
            }
        };
    }

    /**
     * Returns the list of applied Events for this lifecycle instance.
     * <p>
     * Note that this list is not reset when activating or deactivating the lifecycle.
     *
     * @return the list of messages applied while this lifecycle instance was active
     */
    public List<EventMessage> getAppliedEvents() {
        return appliedMessages;
    }

    /**
     * Returns the payloads of the Events applied while this lifecycle instance was active. This is usually the
     * parameter passed to the {@link AggregateLifecycle#apply(Object)} method.
     *
     * @return the payloads of the applied events.
     */
    public List<Object> getAppliedEventPayloads() {
        return appliedMessages.stream().map(EventMessage::payload).collect(Collectors.toList());
    }

    /**
     * Indicates whether an Aggregate has invoked "markDeleted" while this lifecycle was active.
     *
     * @return {@code true} if {@link #markDeleted()} was invoked, otherwise {@code false}
     */
    public boolean isMarkedDeleted() {
        return deleted;
    }
}
