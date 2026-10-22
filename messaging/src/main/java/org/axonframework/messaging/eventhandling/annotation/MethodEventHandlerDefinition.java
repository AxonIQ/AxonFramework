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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Implementation of a {@link HandlerEnhancerDefinition} used for {@link EventHandler} annotated methods to wrap a
 * {@link MessageHandlingMember} in a {@link EventHandlingMember} instance.
 * <p>
 * The {@link EventHandler#eventName()} and {@link EventHandler#event()} attributes define the
 * {@link EventHandlingMember#eventNames(org.axonframework.messaging.core.MessageTypeResolver) event names} of the
 * resulting member.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class MethodEventHandlerDefinition implements HandlerEnhancerDefinition {

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        Optional<String> optionalEventName = original.attribute(HandlerAttributes.EVENT_NAME);
        return original.unwrap(Method.class)
                       .filter(method -> AnnotationUtils.isAnnotationPresent(method, EventHandler.class))
                       .filter(method -> optionalEventName.isPresent())
                       .map(method -> (MessageHandlingMember<T>)
                               new MethodEventHandlerDefinition.MethodEventMessageHandlingMember<>(original)
                       )
                       .orElse(original);
    }

    /**
     * An {@link EventHandlingMember} for {@link EventHandler} annotated methods, deriving its event names from the
     * annotation attributes.
     *
     * @param <T> The type of entity to which the message handler will delegate the actual handling of the message.
     */
    @Internal
    static class MethodEventMessageHandlingMember<T>
            extends WrappedMessageHandlingMember<T>
            implements EventHandlingMember<T> {

        private MethodEventMessageHandlingMember(MessageHandlingMember<T> delegate) {
            super(delegate);
            if (delegate.unwrap(Method.class).isEmpty()) {
                throw new UnsupportedHandlerException(
                        "@EventHandler annotation can only be put on methods.",
                        delegate.unwrap(Member.class).orElse(null)
                );
            }
        }

        @Override
        public boolean canHandle(Message message, ProcessingContext context) {
            return super.canHandle(message, context) && message instanceof EventMessage;
        }
    }
}