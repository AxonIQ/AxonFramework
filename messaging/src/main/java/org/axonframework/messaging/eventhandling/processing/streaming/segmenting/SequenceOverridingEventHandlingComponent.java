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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ResetContext;

import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Decorator implementation of {@link EventHandlingComponent} that uses a configurable {@link SequencingPolicy} to
 * determine the sequence identifier for events, while delegating all other operations to an underlying
 * {@link EventHandlingComponent}.
 * <p>
 * This component first attempts to determine sequence identification using the configured sequencing policy. If the
 * policy returns {@link Optional#empty()}, only then it falls back to the delegate component's sequence identifier.
 * This allows for overriding the sequencing behavior of the underlying component.
 * <p>
 *
 * @author Mateusz Nowak
 * @see SequencingPolicy
 * @see EventHandlingComponent
 * @since 5.0.0
 */
@Internal
public class SequenceOverridingEventHandlingComponent implements EventHandlingComponent {

    private final SequencingPolicy<? super EventMessage> sequencingPolicy;
    private final EventHandlingComponent delegate;

    /**
     * Creates a new {@code SequenceOverridingEventHandlingComponent} that uses the given {@code sequencingPolicy} to
     * override sequence identification while delegating all other operations to the {@code delegate} component.
     *
     * @param sequencingPolicy The policy to use for determining sequence identifiers for events.
     * @param delegate         The underlying event handling component to delegate operations to.
     */
    public SequenceOverridingEventHandlingComponent(SequencingPolicy<? super EventMessage> sequencingPolicy,
                                                    EventHandlingComponent delegate) {
        this.sequencingPolicy = requireNonNull(sequencingPolicy, "SequencingPolicy may not be null");
        this.delegate = requireNonNull(delegate, "Delegate EventHandlingComponent may not be null");
    }

    @Override
    public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
        requireNonNull(event, "Event Message may not be null");
        return sequencingPolicy.sequenceIdentifierFor(event, context)
                               .orElseGet(() -> delegate.sequenceIdentifierFor(event, context));
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return delegate.supportedEvents();
    }

    @Override
    public boolean supports(QualifiedName eventName) {
        return delegate.supports(eventName);
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event,
                                               ProcessingContext context) {
        return delegate.handle(event, context);
    }

    @Override
    public boolean supportsReset() {
        return delegate.supportsReset();
    }

    @Override
    public MessageStream.Empty<Message> handle(ResetContext resetContext,
                                               ProcessingContext context) {
        return delegate.handle(resetContext, context);
    }

    @Override
    public MessageStream.Empty<Message> handle(ReplayStatusChanged statusChange,
                                               ProcessingContext context) {
        return delegate.handle(statusChange, context);
    }

    @Override
    public <C> Optional<C> unwrap(Class<C> componentType) {
        return componentType.isInstance(this)
                ? Optional.of(componentType.cast(this))
                : delegate.unwrap(componentType);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("sequencingPolicy", sequencingPolicy);
    }
}
