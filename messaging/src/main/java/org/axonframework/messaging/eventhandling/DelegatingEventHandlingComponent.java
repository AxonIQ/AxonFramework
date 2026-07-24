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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ResetContext;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Abstract implementation of an {@link EventHandlingComponent} that delegates calls to a given delegate.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
public abstract class DelegatingEventHandlingComponent implements EventHandlingComponent {

    protected final EventHandlingComponent delegate;

    /**
     * Constructs the component with given {@code delegate} to receive calls.
     *
     * @param delegate The instance to delegate calls to.
     */
    public DelegatingEventHandlingComponent(EventHandlingComponent delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate EventHandlingComponent may not be null");
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event,
                                                        ProcessingContext context) {
        return delegate.handle(event, context);
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
    public Object sequenceIdentifierFor(EventMessage event,
                                        ProcessingContext context) {
        return delegate.sequenceIdentifierFor(event, context);
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
    }
}
