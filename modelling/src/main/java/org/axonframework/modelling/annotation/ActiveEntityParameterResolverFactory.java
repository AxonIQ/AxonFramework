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

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;

/**
 * A {@link ParameterResolverFactory} that creates an {@link ActiveEntityParameterResolver}, injecting the active,
 * possibly {@code null}, entity state into {@code static} {@code @EventHandler} (and meta-annotated, such as
 * {@code @EventSourcingHandler}) methods of an entity.
 * <p>
 * Such a static handler forms a functional evolve step. Like any message handler, it receives the event payload as its
 * first parameter. The current entity state is an additional parameter typed as the entity, and the handler returns
 * the next state:
 * <pre>{@code
 * @EventSourcingHandler
 * static Account on(Opened event, @Nullable Account state) {
 *     return new Account(event.accountId(), 0);
 * }
 * }</pre>
 * The state is {@code null} while the entity does not exist yet. The {@link AnnotationBasedEntityEvolvingComponent}
 * provides it while evolving the entity; outside of that, the resolver does not match and the handler is not invoked.
 * <p>
 * This factory is registered through the {@link java.util.ServiceLoader} mechanism, like other parameter resolver
 * factories. It only acts on parameters after the first one of {@code static} {@code @EventHandler} methods, when the
 * parameter is typed as the declaring entity type, one of its super types, or one of its subtypes. It never resolves
 * parameters of instance handlers, command handlers, or other methods.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public class ActiveEntityParameterResolverFactory implements ParameterResolverFactory {

    private static final ActiveEntityParameterResolver ACTIVE_ENTITY_RESOLVER = new ActiveEntityParameterResolver();

    @Nullable
    @Override
    public ParameterResolver<?> createInstance(Executable executable, Parameter[] parameters, int parameterIndex) {
        if (parameterIndex == 0
                || !(executable instanceof Method method)
                || !Modifier.isStatic(method.getModifiers())
                || !AnnotationUtils.isAnnotationPresent(executable, EventHandler.class)) {
            return null;
        }
        return isEntityTyped(parameters[parameterIndex].getType(), method.getDeclaringClass())
                ? ACTIVE_ENTITY_RESOLVER
                : null;
    }

    private static boolean isEntityTyped(Class<?> parameterType, Class<?> declaringClass) {
        if (parameterType == Object.class || Message.class.isAssignableFrom(parameterType)) {
            return false;
        }
        return parameterType.isAssignableFrom(declaringClass) || declaringClass.isAssignableFrom(parameterType);
    }
}
