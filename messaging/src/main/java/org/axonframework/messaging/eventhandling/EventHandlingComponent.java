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

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetHandler;

import java.util.Optional;
import java.util.Set;

/**
 * Interface describing a group of {@code EventHandlers} belonging to a single component.
 * <p>
 * As such, it allows registration of {@code EventHandlers} through the {@code EventHandlerRegistry}. Besides handling
 * and registration, it specifies which {@link #supportedEvents() events} it supports.
 * <p>
 * Additionally, this component supports reset operations through the {@link ResetHandler}. The {@link #supportsReset()}
 * method indicates whether the component can participate in replay operations.
 *
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 3.0.0
 */
public interface EventHandlingComponent
        extends EventHandler, ResetHandler, ReplayStatusChangedHandler, DescribableComponent {

    /**
     * All supported {@link EventMessage events}, referenced through a {@link QualifiedName}.
     *
     * @return All supported {@link EventMessage events}, referenced through a {@link QualifiedName}.
     */
    Set<QualifiedName> supportedEvents();

    /**
     * Checks whether the given {@code eventName} is supported by this component.
     *
     * @param eventName The name of the event to check for support.
     * @return {@code true} if the given {@code eventName} is supported, {@code false} otherwise.
     */
    default boolean supports(QualifiedName eventName) {
        return supportedEvents().contains(eventName);
    }

    /**
     * Returns the sequence identifier for the given {@code event}. When two events have the same sequence identifier
     * (as defined by their equals method), they will be executed sequentially.
     * <b>Important:</b> All {@link EventHandler EventHandlers} for the same
     * {@link org.axonframework.messaging.core.QualifiedName} within a single {@code EventHandlingComponent} must return
     * the same sequence identifier for a given event. Mixing different sequence identifiers within the scope of a
     * single {@code EventHandlingComponent} is not supported and may lead to undefined behavior.
     *
     * @param event   The event for which to get the sequencing identifier.
     * @param context The processing context in which the event is being handled.
     * @return A sequence identifier for the given event.
     */
    Object sequenceIdentifierFor(EventMessage event, ProcessingContext context);

    /**
     * Indicates whether this component supports reset operations.
     * <p>
     * When {@code true}, this component can participate in replay operations and its
     * {@link #handle(ResetContext, ProcessingContext)} method will be called before replay begins.
     * <p>
     * By default, reset is supported.
     *
     * @return {@code true} if this component supports reset operations, {@code false} otherwise.
     */
    default boolean supportsReset() {
        return true;
    }

    /**
     * Resolves a capability of the given {@code componentType} from this component, following the decorator-unwrap
     * convention used elsewhere in the framework (such as
     * {@link org.axonframework.messaging.core.annotation.MessageHandlingMember#unwrap(Class)}).
     * <p>
     * By default a component resolves itself when it is an instance of {@code componentType}, and
     * {@link Optional#empty()} otherwise. Decorating components forward this call through their delegate chain, and
     * wrapping components may also expose the object they wrap.
     *
     * @param componentType the capability type to resolve
     * @param <C>           the capability type
     * @return an {@link Optional} holding the resolved capability, or empty if not available
     * @since 5.2.0
     */
    default <C> Optional<C> unwrap(Class<C> componentType) {
        return componentType.isInstance(this) ? Optional.of(componentType.cast(this)) : Optional.empty();
    }

    @Override
    default MessageStream.Empty<Message> handle(ResetContext resetContext, ProcessingContext context) {
        return MessageStream.empty();
    }

    @Override
    default MessageStream.Empty<Message> handle(ReplayStatusChanged statusChange, ProcessingContext context) {
        return MessageStream.empty();
    }
}
