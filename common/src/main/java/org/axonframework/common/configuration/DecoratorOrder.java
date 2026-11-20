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

/**
 * Utility class containing constants for the common cross-cutting concerns provided by the framework, to be used as the
 * {@code order} when {@link ComponentRegistry#registerDecorator(Class, int, ComponentDecorator) registering} a
 * {@link ComponentDecorator} or {@link DecoratorDefinition}.
 * <p>
 * Decorators with a lower order are applied first, and thus sit closer to the decorated component. Decorators with a
 * higher order are applied later and wrap the earlier ones. Having all orders in a single place allows decorators to be
 * positioned relative to one another without referencing constants of each other's classes.
 * <p>
 * Example usage:
 * <pre>{@code
 * configurer.componentRegistry(registry -> registry.registerDecorator(
 *         CommandBus.class,
 *         DecoratorOrder.INTERCEPTING + 10,
 *         (config, name, delegate) -> new CustomCommandBus(delegate)
 * ));
 * }</pre>
 *
 * @author Steven van Beelen
 * @see ComponentRegistry#registerDecorator(Class, int, ComponentDecorator)
 * @see ComponentRegistry#registerDecorator(DecoratorDefinition)
 * @since 5.5.0
 */
public final class DecoratorOrder {

    private DecoratorOrder() {
        // Utility class
    }

    /**
     * Order of the decorator that is applied first, resulting in the decorator closest to the decorated component.
     */
    public static final int INNERMOST = Integer.MIN_VALUE;

    /**
     * Order of the decorator that is applied last, resulting in the decorator that is the outermost wrapper of the
     * decorated component.
     */
    public static final int OUTERMOST = Integer.MAX_VALUE;

    /**
     * Order of the decorators that intercept messages, such as the intercepting command bus, event bus, event sink,
     * event handling component, and query bus.
     */
    public static final int INTERCEPTING = INNERMOST + 100;

    /**
     * Order of the decorator that converts payloads on a gateway. It is positioned at the same level as
     * {@link #INTERCEPTING}.
     */
    public static final int CONVERTING = INNERMOST + 150;

    /**
     * Order of the decorator that retries failed commands. It is positioned outside the {@link #INTERCEPTING}
     * decorators, so that retried commands pass through the interceptor chain again.
     */
    public static final int RETRYING = INNERMOST + 1000;

    /**
     * Default order for decorators that have no ordering requirements relative to other decorators.
     */
    public static final int DEFAULT = 0;

    /**
     * Order of the tracing decorators. They are positioned outside of the {@link #INTERCEPTING} and {@link #RETRYING}
     * decorators, resulting in a stack of {@code Tracing(...(Intercepting(Simple...)))}, yet leave room for custom
     * decorators to wrap them.
     */
    public static final int TRACING = OUTERMOST - 1000;

    /**
     * Order of the decorators that late-bind a hierarchical, parent-aware variant of a component. It is applied late,
     * yet still allows users to register decorators after it.
     */
    public static final int HIERARCHICAL = OUTERMOST >> 1;
}
