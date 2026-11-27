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

import org.axonframework.common.StringUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;

import java.util.Optional;

/**
 * Interface indicating that a {@link MessageHandlingMember} is capable of handling specific event messages.
 *
 * @param <T> The type of entity to which the message handler will delegate the actual handling of the message.
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
public interface EventHandlingMember<T> extends MessageHandlingMember<T> {

    /**
     * Returns the name of the event that can be handled.
     * <p>
     * Might be an empty {@link String} when undefined by this handling member, in which case components gathering
     * {@code EventHandlingMembers} should fall back to other mechanisms to define the name of a handling member.
     *
     * @return The name of the event that can be handled.
     */
    String eventName();

    /**
     * Resolves the {@link QualifiedName} of the events the given {@code handler} handles.
     * <p>
     * The name is resolved as follows:
     * <ol>
     *     <li>A non-empty {@link #eventName()} of the {@code handler} is used as is.</li>
     *     <li>Otherwise, the {@link HandlerAttributes#EVENT_NAMESPACE namespace} and
     *     {@link HandlerAttributes#EVENT_LOCAL_NAME local name} attributes of the {@code handler} are used. A part
     *     that is undefined is taken from the name the {@link MessageHandlingMember#payloadType() payload type}
     *     resolves to.</li>
     *     <li>Otherwise, the name the payload type resolves to through the given {@code messageTypeResolver} is
     *     used.</li>
     * </ol>
     *
     * @param handler             the handler to resolve the handled event name of
     * @param messageTypeResolver the resolver deriving the name of the payload type of the {@code handler}
     * @return the qualified name of the events the given {@code handler} handles
     * @since 5.5.0
     */
    static QualifiedName qualifiedNameOf(MessageHandlingMember<?> handler, MessageTypeResolver messageTypeResolver) {
        Optional<String> eventName = handler.unwrap(EventHandlingMember.class)
                                            .map(EventHandlingMember::eventName)
                                            .filter(StringUtils::nonEmpty);
        if (eventName.isPresent()) {
            return new QualifiedName(eventName.get());
        }
        String namespace = handler.<String>attribute(HandlerAttributes.EVENT_NAMESPACE).orElse("");
        String localName = handler.<String>attribute(HandlerAttributes.EVENT_LOCAL_NAME).orElse("");
        if (!namespace.isEmpty() && !localName.isEmpty()) {
            return new QualifiedName(namespace, localName);
        }
        QualifiedName payloadName = messageTypeResolver.resolveOrThrow(handler.payloadType()).qualifiedName();
        if (namespace.isEmpty() && localName.isEmpty()) {
            return payloadName;
        }
        return new QualifiedName(namespace.isEmpty() ? payloadName.namespace() : namespace,
                                 localName.isEmpty() ? payloadName.localName() : localName);
    }
}