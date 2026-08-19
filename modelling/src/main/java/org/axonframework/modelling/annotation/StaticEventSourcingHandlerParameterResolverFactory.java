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

package org.axonframework.modelling.annotation;

import org.axonframework.common.Priority;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.PayloadParameterResolver;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;

/**
 * A {@link ParameterResolverFactory} enabling {@code static} {@code @EventHandler} (and meta-annotated, such as
 * {@code @EventSourcingHandler}) methods to receive the current entity state as their first argument. Such a static
 * handler forms a functional evolve step of the shape {@code (@Nullable State, Event) -> State}: it can create the
 * entity from a {@code null} state, evolve an existing instance, or return {@code null} to remove it.
 * <p>
 * Because the framework treats a handler's first parameter as the message payload, this factory rearranges parameter
 * resolution for these handlers: the parameter typed as the entity (the state) is resolved through an
 * {@link ActiveEntityParameterResolver}, while the payload {@link PayloadParameterResolver} is bound to the first
 * remaining, non-annotated, non-{@link Message}/{@link Metadata} parameter (the event). All other parameters are left
 * to the regular resolver chain.
 * <p>
 * The factory only acts on {@code static}, {@code @EventHandler}-annotated methods whose declaring type appears as a
 * parameter; it never interferes with instance handlers, command handlers, or unrelated static methods. It is scoped to
 * entity handler inspection and is not registered globally.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@Internal
@Priority(Priority.HIGH)
public class StaticEventSourcingHandlerParameterResolverFactory implements ParameterResolverFactory {

    private final ActiveEntityParameterResolver activeEntityResolver = new ActiveEntityParameterResolver();

    @Nullable
    @Override
    public ParameterResolver<?> createInstance(Executable executable, Parameter[] parameters, int parameterIndex) {
        if (!(executable instanceof Method method) || !Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        if (!AnnotationUtils.isAnnotationPresent(executable, EventHandler.class)) {
            return null;
        }
        int stateIndex = stateParameterIndex(method, parameters);
        if (stateIndex < 0) {
            // Not a state-first static handler; leave resolution to the regular chain.
            return null;
        }
        if (parameterIndex == stateIndex) {
            return activeEntityResolver;
        }
        if (parameterIndex == payloadParameterIndex(parameters, stateIndex)) {
            return new PayloadParameterResolver(parameters[parameterIndex].getType());
        }
        return null;
    }

    private static int stateParameterIndex(Method method, Parameter[] parameters) {
        Class<?> declaringClass = method.getDeclaringClass();
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameterType = parameters[i].getType();
            if (!Message.class.isAssignableFrom(parameterType)
                    && (parameterType.isAssignableFrom(declaringClass)
                    || declaringClass.isAssignableFrom(parameterType))) {
                return i;
            }
        }
        return -1;
    }

    private static int payloadParameterIndex(Parameter[] parameters, int stateIndex) {
        for (int i = 0; i < parameters.length; i++) {
            if (i == stateIndex) {
                continue;
            }
            Parameter parameter = parameters[i];
            Class<?> parameterType = parameter.getType();
            if (Message.class.isAssignableFrom(parameterType) || Metadata.class.isAssignableFrom(parameterType)) {
                continue;
            }
            if (parameter.getAnnotations().length > 0) {
                continue;
            }
            return i;
        }
        return -1;
    }
}
