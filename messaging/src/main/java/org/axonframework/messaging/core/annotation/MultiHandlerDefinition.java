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

import org.axonframework.common.annotation.PriorityAnnotationComparator;
import org.axonframework.messaging.core.MessageStream;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

/**
 * HandlerDefinition instance that delegates to multiple other instances, in the order provided. Also, it wraps the
 * delegate with {@link HandlerEnhancerDefinition}.
 *
 * @author Tyler Thrailkill
 * @author Milan Savic
 * @since 3.3.0
 */
public class MultiHandlerDefinition implements HandlerDefinition {

    private final List<HandlerDefinition> handlerDefinitions;
    private final HandlerEnhancerDefinition handlerEnhancerDefinition;

    /**
     * Creates a MultiHandlerDefinition instance with the given {@code delegates}, which are automatically ordered based
     * on the {@link org.axonframework.common.Priority @Priority} annotation on their respective classes. Classes with
     * the same Priority are kept in the order as provided in the {@code delegates}. As an enhancer,
     * {@link ClasspathHandlerEnhancerDefinition} is used.
     * <p>
     * If one of the delegates is a MultiHandlerDefinition itself, that factory's delegates are 'mixed' with the given
     * {@code delegates}, based on their respective order.
     *
     * @param delegates The delegates to include in the factory
     * @return an instance delegating to the given {@code delegates}
     */
    public static MultiHandlerDefinition ordered(HandlerDefinition... delegates) {
        return ordered(Arrays.asList(delegates));
    }

    /**
     * Creates a MultiHandlerDefinition instance with the given {@code delegates}, which are automatically ordered based
     * on the {@link org.axonframework.common.Priority @Priority} annotation on their respective classes. Classes with
     * the same Priority are kept in the order as provided in the {@code delegates}. As an enhancer, provided one is
     * used.
     * <p>
     * If one of the delegates is a MultiHandlerDefinition itself, that factory's delegates are 'mixed' with the given
     * {@code delegates}, based on their respective order.
     *
     * @param handlerEnhancerDefinition The enhancer used to wrap the delegates
     * @param delegates                 The delegates to include in the factory
     * @return an instance delegating to the given {@code delegates}
     */
    public static MultiHandlerDefinition ordered(HandlerEnhancerDefinition handlerEnhancerDefinition,
                                                 HandlerDefinition... delegates) {
        return ordered(Arrays.asList(delegates), handlerEnhancerDefinition);
    }

    /**
     * Creates a MultiHandlerDefinition instance with the given {@code delegates}, which are automatically ordered based
     * on the {@link org.axonframework.common.Priority @Priority} annotation on their respective classes. Classes with
     * the same Priority are kept in the order as provided in the {@code delegates}. As an enhancer,
     * {@link ClasspathHandlerEnhancerDefinition} is used.
     * <p>
     * If one of the delegates is a MultiHandlerDefinition itself, that factory's delegates are 'mixed' with the given
     * {@code delegates}, based on their respective order.
     *
     * @param delegates The delegates to include in the factory
     * @return an instance delegating to the given {@code delegates}
     */
    public static MultiHandlerDefinition ordered(List<HandlerDefinition> delegates) {
        return new MultiHandlerDefinition(delegates);
    }

    /**
     * Creates a MultiHandlerDefinition instance with the given {@code delegates}, which are automatically ordered based
     * on the {@link org.axonframework.common.Priority @Priority} annotation on their respective classes. Classes with
     * the same Priority are kept in the order as provided in the {@code delegates}. As an enhancer, provided one is
     * used.
     * <p>
     * If one of the delegates is a MultiHandlerDefinition itself, that factory's delegates are 'mixed' with the given
     * {@code delegates}, based on their respective order.
     *
     * @param delegates                 The delegates to include in the factory
     * @param handlerEnhancerDefinition The enhancer used to wrap the delegates
     * @return an instance delegating to the given {@code delegates}
     */
    public static MultiHandlerDefinition ordered(List<HandlerDefinition> delegates,
                                                 HandlerEnhancerDefinition handlerEnhancerDefinition) {
        return new MultiHandlerDefinition(delegates, MultiHandlerEnhancerDefinition.ordered(handlerEnhancerDefinition));
    }

    /**
     * Initializes an instance that delegates to the given {@code delegates}, in the order provided. Changes in the
     * given array are not reflected in the created instance.
     *
     * @param delegates The handlerDefinitions providing the parameter values to use
     */
    public MultiHandlerDefinition(HandlerDefinition... delegates) {
        this(Arrays.asList(delegates));
    }

    /**
     * Initializes an instance that delegates to the given {@code delegates}, in the order provided. Changes in the
     * given list are not reflected in the created instance.
     *
     * @param delegates The handlerDefinitions providing the parameter values to use
     */
    public MultiHandlerDefinition(List<HandlerDefinition> delegates) {
        this(delegates,
             ClasspathHandlerEnhancerDefinition.forClassLoader(Thread.currentThread().getContextClassLoader()));
    }

    /**
     * Initializes an instance that delegates to the given {@code delegates}, in the order provided. Changes in the
     * given List are not reflected in the created instance.
     *
     * @param delegates                 The list of handlerDefinitions providing the parameter values to use
     * @param handlerEnhancerDefinition The enhancer used to wrap the delegates
     */
    public MultiHandlerDefinition(List<HandlerDefinition> delegates,
                                  HandlerEnhancerDefinition handlerEnhancerDefinition) {
        this.handlerDefinitions = flatten(delegates);
        this.handlerEnhancerDefinition = handlerEnhancerDefinition;
    }

    private static List<HandlerDefinition> flatten(List<HandlerDefinition> handlerDefinitions) {
        List<HandlerDefinition> flattened = new ArrayList<>(handlerDefinitions.size());
        for (HandlerDefinition handlerDefinition : handlerDefinitions) {
            if (handlerDefinition instanceof MultiHandlerDefinition) {
                flattened.addAll(((MultiHandlerDefinition) handlerDefinition).getDelegates());
            } else {
                flattened.add(handlerDefinition);
            }
        }
        flattened.sort(PriorityAnnotationComparator.getInstance());
        return flattened;
    }

    /**
     * Returns the delegates of this instance, in the order they are evaluated to resolve parameters.
     *
     * @return the delegates of this instance, in the order they are evaluated to resolve parameters
     */
    public List<HandlerDefinition> getDelegates() {
        return Collections.unmodifiableList(handlerDefinitions);
    }

    /**
     * Returns handler enhancer definition used to wrap handlers.
     *
     * @return handler enhancer definition
     */
    public HandlerEnhancerDefinition getHandlerEnhancerDefinition() {
        return handlerEnhancerDefinition;
    }

    @Override
    public <T> Optional<MessageHandlingMember<T>> createHandler(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        Optional<MessageHandlingMember<T>> handler = Optional.empty();
        for (HandlerDefinition handlerDefinition : handlerDefinitions) {
            handler = handlerDefinition.createHandler(declaringType,
                                                      method,
                                                      parameterResolverFactory,
                                                      messageStreamResolver);
            if (handler.isPresent()) {
                return Optional.of(handlerEnhancerDefinition.wrapHandler(handler.get()));
            }
        }
        return handler;
    }

    @Override
    public <T> List<MessageHandlingMember<T>> createHandlers(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        for (HandlerDefinition handlerDefinition : handlerDefinitions) {
            List<MessageHandlingMember<T>> handlers = handlerDefinition.createHandlers(declaringType,
                                                                                       method,
                                                                                       parameterResolverFactory,
                                                                                       messageStreamResolver);
            if (!handlers.isEmpty()) {
                return handlers.stream()
                               .map(handlerEnhancerDefinition::wrapHandler)
                               .toList();
            }
        }
        return List.of();
    }
}
