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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Interface that describes an object capable of inspecting a method to determine if the method is suitable for message
 * handling. If the method is suitable the definition returns a {@link MessageHandler} instance to invoke the method.
 *
 * @author Allard Buijze
 * @since 2.1.0
 */
public interface HandlerDefinition {

    /**
     * Create a {@link MessageHandlingMember} for the given {@code method} method. Use the given
     * {@code parameterResolverFactory} to resolve the method's parameters.
     *
     * @param <T>                      The type of the declaring object
     * @param declaringType            The type of object declaring the given method
     * @param method                   The method to inspect
     * @param parameterResolverFactory Factory for a {@link ParameterResolver} of the method
     * @param messageStreamResolver    The lambda resolving a {@link MessageStream} out of the result from
     *                                 {@link MessageHandlingMember#handle(Message, ProcessingContext, Object)}.
     * @return An optional containing the handler if the method is suitable, or an empty Nullable otherwise.
     */
    <T> Optional<MessageHandlingMember<T>> createHandler(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    );

    /**
     * Create the {@link MessageHandlingMember MessageHandlingMembers} for the given {@code method}. Use the given
     * {@code parameterResolverFactory} to resolve the method's parameters.
     * <p>
     * A method results in several members when it declares several message handler annotations, for example by
     * repeating {@link org.axonframework.messaging.eventhandling.annotation.EventHandler @EventHandler}. Each member
     * then describes one of those annotations.
     * <p>
     * Defaults to the result of
     * {@link #createHandler(Class, Method, ParameterResolverFactory, Function) createHandler(...)}, thus at most one
     * member per method.
     *
     * @param <T>                      the type of the declaring object
     * @param declaringType            the type of object declaring the given method
     * @param method                   the method to inspect
     * @param parameterResolverFactory factory for a {@link ParameterResolver} of the method
     * @param messageStreamResolver    the lambda resolving a {@link MessageStream} out of the result from
     *                                 {@link MessageHandlingMember#handle(Message, ProcessingContext, Object)}
     * @return the handlers for the given {@code method}, or an empty list if the method is not suitable
     * @since 5.5.0
     */
    default <T> List<MessageHandlingMember<T>> createHandlers(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        return createHandler(declaringType, method, parameterResolverFactory, messageStreamResolver)
                .map(List::of)
                .orElseGet(List::of);
    }
}
