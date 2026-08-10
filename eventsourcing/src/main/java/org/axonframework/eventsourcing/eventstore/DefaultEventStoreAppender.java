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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.util.List;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of the {@link EventStoreAppender}.
 * <p>
 * Delegates the inherited {@link EventAppender#append(List) append} behavior to a plain {@link EventAppender}
 * created for the exact same {@link ProcessingContext} and {@link EventStore}, and implements
 * {@link #conditional(EventCriteria)}, {@link #conditional(AppendCondition)}, and {@link #conditional(UnaryOperator)}
 * on top of {@link EventStoreTransaction#overrideAppendCondition(UnaryOperator)} for the {@link EventStore}'s
 * {@link EventStore#transaction(ProcessingContext) transaction} of that same {@code ProcessingContext} - guaranteeing
 * that condition registration and event publication always target the same store and transaction.
 * <p>
 * Package-private; obtain instances through {@link EventStoreAppender#forContext(ProcessingContext)}.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
class DefaultEventStoreAppender implements EventStoreAppender {

    private final ProcessingContext processingContext;
    private final EventStore eventStore;
    private final EventAppender delegate;

    DefaultEventStoreAppender(
            ProcessingContext processingContext,
            EventStore eventStore,
            MessageTypeResolver messageTypeResolver
    ) {
        this.processingContext = requireNonNull(processingContext, "The processingContext cannot be null");
        this.eventStore = requireNonNull(eventStore, "The eventStore cannot be null");
        this.delegate = EventAppender.forContext(processingContext, eventStore, messageTypeResolver);
    }

    @Override
    public void append(List<?> events) {
        delegate.append(events);
    }

    @Override
    public void append(List<?> events, Metadata metadata) {
        delegate.append(events, metadata);
    }

    @Override
    public EventStoreAppender conditional(EventCriteria criteria) {
        requireNonNull(criteria, "The criteria cannot be null");
        return conditional(current -> {
            if (AppendCondition.none().equals(current)) {
                return AppendCondition.withCriteria(criteria);
            }
            AppendCriteriaCoordinator.assertCriteriaReplacementSupported(current.consistencyMarker());
            return current.replaceCriteria(criteria);
        });
    }

    @Override
    public EventStoreAppender conditional(AppendCondition condition) {
        requireNonNull(condition, "The condition cannot be null");
        return conditional(current -> condition);
    }

    @Override
    public EventStoreAppender conditional(UnaryOperator<AppendCondition> transformation) {
        requireNonNull(transformation, "The transformation cannot be null");
        eventStore.transaction(processingContext).overrideAppendCondition(transformation);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("processingContext", processingContext);
        descriptor.describeProperty("eventStore", eventStore);
        descriptor.describeProperty("delegate", delegate);
    }
}
