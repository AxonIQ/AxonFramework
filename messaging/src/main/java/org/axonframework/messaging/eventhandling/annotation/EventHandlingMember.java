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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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
     * Returns the names of the events the given {@code member} handles.
     * <p>
     * When the given {@code member} is, or wraps, an {@code EventHandlingMember}, the names are taken from
     * {@link #eventNames(MessageTypeResolver)}. Otherwise, the result contains the single name the
     * {@link MessageHandlingMember#payloadType() payload type} of the {@code member} resolves to through the given
     * {@code messageTypeResolver}.
     *
     * @param member              the handler to return the event names for
     * @param messageTypeResolver the resolver used to derive the name of the payload type of the {@code member}
     * @return the names of the events the given {@code member} handles, never empty
     * @since 5.5.0
     */
    static Set<QualifiedName> eventNamesOf(MessageHandlingMember<?> member, MessageTypeResolver messageTypeResolver) {
        return member.unwrap(EventHandlingMember.class)
                     .map(eventMember -> ((EventHandlingMember<?>) eventMember).eventNames(messageTypeResolver))
                     .orElseGet(() -> Set.of(messageTypeResolver.resolveOrThrow(member.payloadType())
                                                                .qualifiedName()));
    }

    /**
     * Returns the names of the events this member handles.
     * <p>
     * The names are taken from the {@link HandlerAttributes#EVENT_NAME} and
     * {@link HandlerAttributes#EVENT_QUALIFIED_NAMES} attributes, as declared through the deprecated
     * {@link EventHandler#eventName()} and {@link EventHandler#event()} respectively. A name declared through
     * {@link EventHandler#eventName()} is used as is, whereas an empty namespace or local name of a
     * {@link org.axonframework.messaging.core.annotation.QualifiedName} declared through {@link EventHandler#event()}
     * defaults to the respective part of the name the {@link #payloadType()} resolves to through the given
     * {@code messageTypeResolver}.
     * <p>
     * When no names are declared at all, the result contains the single name the {@link #payloadType()} resolves to
     * through the given {@code messageTypeResolver}. The {@code messageTypeResolver} is only consulted when the
     * declared names require it.
     *
     * @param messageTypeResolver the resolver used to derive the name of the {@link #payloadType()}, used for undeclared
     *                            (parts of) names
     * @return the names of the events this member handles, never empty
     * @since 5.5.0
     */
    default Set<QualifiedName> eventNames(MessageTypeResolver messageTypeResolver) {
        Supplier<QualifiedName> payloadName =
                () -> messageTypeResolver.resolveOrThrow(payloadType()).qualifiedName();
        Set<QualifiedName> names = new LinkedHashSet<>();
        this.<String>attribute(HandlerAttributes.EVENT_NAME)
            .filter(StringUtils::nonEmptyOrNull)
            .map(QualifiedName::new)
            .ifPresent(names::add);
        this.<org.axonframework.messaging.core.annotation.QualifiedName[]>attribute(
                HandlerAttributes.EVENT_QUALIFIED_NAMES
        ).ifPresent(declaredNames -> Arrays.stream(declaredNames)
                                           .map(declared -> resolve(declared, payloadName))
                                           .forEach(names::add));
        if (names.isEmpty()) {
            names.add(payloadName.get());
        }
        return Collections.unmodifiableSet(names);
    }

    private static QualifiedName resolve(org.axonframework.messaging.core.annotation.QualifiedName declared,
                                         Supplier<QualifiedName> payloadName) {
        String namespace = declared.namespace();
        String localName = declared.name();
        if (namespace.isEmpty() || localName.isEmpty()) {
            // Only consult the resolver when a part is missing, as resolving might fail for some payload types.
            QualifiedName defaults = payloadName.get();
            // The namespace of the payload type's name is nullable, while the QualifiedName constructor is not.
            namespace = namespace.isEmpty() ? Objects.requireNonNullElse(defaults.namespace(), "") : namespace;
            localName = localName.isEmpty() ? defaults.localName() : localName;
        }
        return new QualifiedName(namespace, localName);
    }
}
