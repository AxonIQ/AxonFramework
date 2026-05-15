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

package org.axonframework.common.property;

import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;


/**
 * Abstract Strategy that provides access to all PropertyAccessStrategy implementations.
 * <p/>
 * Application developers may provide custom PropertyAccessStrategy implementations using the ServiceLoader
 * mechanism. To do so, place a file called {@code org.axonframework.common.property.PropertyAccessStrategy}
 * in the {@code META-INF/services} folder. In this file, place the fully qualified class names of all available
 * implementations.
 * <p/>
 * The factory implementations must be public, non-abstract, have a default public constructor and extend the
 * PropertyAccessStrategy class.
 * <p/>
 * Note that this class is not considered public API and may undergo incompatible changes between versions.
 *
 * @author Maxim Fedorov
 * @author Allard Buijze
 * @see java.util.ServiceLoader
 * @see java.util.ServiceLoader#load(Class)
 * @since 2.0
 */
public abstract class PropertyAccessStrategy implements Comparable<PropertyAccessStrategy> {

    private static final ServiceLoader<PropertyAccessStrategy> LOADER =
            ServiceLoader.load(PropertyAccessStrategy.class);

    private static final SortedSet<PropertyAccessStrategy> STRATEGIES = new ConcurrentSkipListSet<>();

    static {
        for (PropertyAccessStrategy factory : LOADER) {
            STRATEGIES.add(factory);
        }
    }

    /**
     * Registers a PropertyAccessStrategy implementation at runtime.
     * Annotated handlers that have already been inspected will not be able to use the newly added strategy.
     *
     * @param strategy implementation to register
     */
    public static void register(PropertyAccessStrategy strategy) {
        STRATEGIES.add(strategy);
    }

    /**
     * Removes all strategies registered using the {@link #register(PropertyAccessStrategy)} method.
     *
     * @param strategy The strategy instance to deregister.
     */
    public static void unregister(PropertyAccessStrategy strategy) {
        STRATEGIES.remove(strategy);
    }

    /**
     * Iterates over all known PropertyAccessStrategy implementations to create a {@link Property} instance for the
     * given parameters. Strategies are invoked in the order they are found on the classpath. The first to provide a
     * suitable Property instance will be used.
     *
     * @param targetClass  class that contains property
     * @param propertyName name of the property to create propertyReader for
     * @param <T>          Thy type defining the property
     * @return suitable {@link Property}, or {@code null} if none is found
     */
    public static @Nullable <T> Property<T> getProperty(Class<? extends T> targetClass, String propertyName) {
        Property<T> property = null;
        Iterator<PropertyAccessStrategy> strategies = STRATEGIES.iterator();
        while (property == null && strategies.hasNext()) {
            property = strategies.next().propertyFor(targetClass, propertyName);
        }

        return property;
    }

    @Override
    public final int compareTo(PropertyAccessStrategy o) {
        if (o == this) {
            return 0;
        }
        final int diff = o.getPriority() - getPriority();
        if (diff == 0) {
            // we don't want equality...
            return getClass().getName().compareTo(o.getClass().getName());
        }
        return diff;
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof PropertyAccessStrategy pas) && compareTo(pas) == 0;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * The priority of this strategy. In general, implementations that have a higher certainty to provide a good
     * Property instance for any given property name should have a higher priority. When two instances have the same
     * priority, their relative order is undefined.
     * <p/>
     * The JavaBean Property strategy has a value of 0. To ensure evaluation before that strategy, use any value higher
     * than that number, otherwise lower.
     *
     * @return a value reflecting relative priority, {@code Integer.MAX_VALUE} being evaluated first
     */
    protected abstract int getPriority();

    /**
     * Returns a Property instance for the given {@code property}, defined in given
     * {@code targetClass}, or {@code null} if no such property is found on the class.
     *
     * @param targetClass The class on which to find the property
     * @param property    The name of the property to find
     * @param <T>         The type of class on which to find the property
     * @return the Property instance providing access to the property value, or {@code null} if property could not
     * be found.
     */
    protected abstract <T> Property<T> propertyFor(Class<? extends T> targetClass, String property);
}

