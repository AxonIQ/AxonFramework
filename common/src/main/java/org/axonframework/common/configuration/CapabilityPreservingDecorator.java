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

package org.axonframework.common.configuration;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Wraps a {@link ComponentDecorator} so its output always implements every interface the {@code delegate} it was
 * given implements, even when the wrapped decorator's own output is narrower.
 * <p>
 * A component may be registered under a single, most-specific {@link Component.Identifier} (for example
 * {@code EventStore.class}) and still be reached by a decorator written against a broader, assignable type (for
 * example one registered for {@code EventBus.class}), because
 * {@link DecoratorDefinition.CompletedDecoratorDefinition#matches} matches by assignability, not exact type
 * equality. If that decorator's own output implements only the narrower type it was written for, the component
 * loses every interface it had beyond that one for any caller relying on the wider set.
 * <p>
 * After the wrapped {@code decorator} runs, this compares the interfaces implemented by its output against the
 * interfaces implemented by the original {@code delegate}. If nothing was lost, the decorator's own output is
 * returned unchanged. Otherwise, a {@link Proxy} is returned instead, implementing the full original interface set:
 * a method declared on an interface the decorated output still implements is routed to that output, so the
 * decoration itself still applies; a method declared on an interface only the original {@code delegate} implements
 * is routed straight to the {@code delegate}.
 * <p>
 * This applies to a component reached through a single {@link Component.Identifier} via assignability. It does not
 * apply to a component deliberately registered under two sibling types that do not extend one another (for example
 * {@code EventStorageEngine} and {@code SnapshotStore} on the same instance) -- those are two separate
 * {@code Identifier} entries with independently-resolving decorator chains.
 *
 * @author John Hendrikx
 */
public final class CapabilityPreservingDecorator {

    private CapabilityPreservingDecorator() {
        // Utility class.
    }

    /**
     * Wraps the given {@code decorator} so its output is guaranteed to still implement every interface the
     * {@code delegate} it receives implements, even if {@code decorator} itself only returns something narrower.
     *
     * @param decorator The decorator to protect against narrowing its delegate.
     * @param <C>       The declared type of the component being decorated.
     * @param <D>       The type the given {@code decorator} itself produces.
     * @return A decorator with identical behavior, except its result is widened back to the full interface set of the
     * {@code delegate} it was given whenever the given {@code decorator} would otherwise have narrowed it.
     */
    @SuppressWarnings("unchecked")
    public static <C, D extends C> ComponentDecorator<C, C> preservingCapabilitiesOf(ComponentDecorator<C, D> decorator) {
        requireNonNull(decorator, "The decorator must not be null.");
        return (config, name, delegate) -> {
            D decorated = decorator.decorate(config, name, delegate);
            if (decorated == null) {
                return null;
            }

            Set<Class<?>> delegateInterfaces = allInterfacesOf(delegate.getClass());
            Set<Class<?>> decoratedInterfaces = allInterfacesOf(decorated.getClass());
            if (decoratedInterfaces.containsAll(delegateInterfaces)) {
                // Nothing was lost -- return the decorator's own output untouched, no proxying needed.
                return decorated;
            }

            return (C) widen(delegate, decorated, delegateInterfaces);
        };
    }

    /**
     * Builds a proxy implementing every interface in {@code interfaces}, routing a method to {@code preferred} when
     * its declaring class is implemented by {@code preferred}, and to {@code fallback} otherwise.
     */
    private static Object widen(Object fallback, Object preferred, Set<Class<?>> interfaces) {
        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> declaringClass = method.getDeclaringClass();
            Object target = declaringClass.isInstance(preferred) ? preferred : fallback;
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return Proxy.newProxyInstance(
                fallback.getClass().getClassLoader(),
                interfaces.toArray(new Class<?>[0]),
                handler
        );
    }

    private static Set<Class<?>> allInterfacesOf(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<>();
        collectInterfaces(type, result);
        return result;
    }

    private static void collectInterfaces(@Nullable Class<?> type, Set<Class<?>> into) {
        if (type == null || type == Object.class) {
            return;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (into.add(iface)) {
                collectInterfaces(iface, into);
            }
        }
        collectInterfaces(type.getSuperclass(), into);
    }
}
