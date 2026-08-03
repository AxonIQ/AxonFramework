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

package org.axonframework.common.nullability;

import org.axonframework.common.annotation.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.WeakHashMap;

import static java.util.ServiceLoader.load;

/**
 * Locates {@link NullabilityResolver} instances on the class path using the {@link ServiceLoader} mechanism, and
 * consults them in descending {@link NullabilityResolver#priority()} order.
 * <p>
 * Resolvers are located with the class loader of the class declaring the parameter under inspection, and cached per
 * class loader, following the same approach as the other class-path-discovered services in this framework. Kept
 * package-private and separate from {@link NullabilityResolver} because an interface cannot hold the state this
 * requires. Callers reach this through {@link NullabilityResolver#nullabilityOf(Parameter)}.
 * <p>
 * Marked {@link Internal} because it exists purely to hold that state, and carries no contract of its own.
 *
 * @author Mateusz Nowak
 * @see ServiceLoader
 * @since 5.3.0
 */
@Internal
final class NullabilityResolverChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(NullabilityResolverChain.class);
    private static final Object MONITOR = new Object();
    private static final Map<ClassLoader, WeakReference<List<NullabilityResolver>>> RESOLVERS = new WeakHashMap<>();

    private NullabilityResolverChain() {
        // not meant to be publicly instantiated
    }

    static Nullability resolve(Parameter parameter) {
        List<NullabilityResolver> resolvers = forClass(parameter.getDeclaringExecutable().getDeclaringClass());
        for (NullabilityResolver resolver : resolvers) {
            Nullability nullability = resolver.resolve(parameter);
            if (nullability != Nullability.UNKNOWN) {
                return nullability;
            }
        }
        return Nullability.UNKNOWN;
    }

    /**
     * Returns the resolvers visible to the given {@code clazz}. Effectively, the class loader of the given class is
     * used to locate implementations, so that a resolver shipped alongside the inspected code is found.
     *
     * @param clazz the class declaring the parameter the resolvers are located for
     * @return the resolvers to consult, in descending priority order
     */
    private static List<NullabilityResolver> forClass(Class<?> clazz) {
        return forClassLoader(clazz == null ? null : clazz.getClassLoader());
    }

    private static List<NullabilityResolver> forClassLoader(ClassLoader classLoader) {
        synchronized (MONITOR) {
            List<NullabilityResolver> resolvers;
            if (!RESOLVERS.containsKey(classLoader)) {
                resolvers = findDelegates(classLoader);
                RESOLVERS.put(classLoader, new WeakReference<>(resolvers));
                return resolvers;
            }
            resolvers = RESOLVERS.get(classLoader).get();
            if (resolvers == null) {
                resolvers = findDelegates(classLoader);
                RESOLVERS.put(classLoader, new WeakReference<>(resolvers));
            }
            return resolvers;
        }
    }

    private static List<NullabilityResolver> findDelegates(ClassLoader classLoader) {
        Iterator<NullabilityResolver> iterator = load(NullabilityResolver.class, classLoader == null
                ? Thread.currentThread().getContextClassLoader()
                : classLoader).iterator();
        List<NullabilityResolver> resolvers = new ArrayList<>();
        while (iterator.hasNext()) {
            try {
                resolvers.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                LOGGER.info(
                        "NullabilityResolver instance ignored, as one of the required classes is not available on the classpath: {}",
                        e.getMessage()
                );
            } catch (NoClassDefFoundError e) {
                LOGGER.info("NullabilityResolver instance ignored. It relies on a class that cannot be found: {}",
                            e.getMessage());
            }
        }
        resolvers.sort(Comparator.comparingInt(NullabilityResolver::priority).reversed());
        return List.copyOf(resolvers);
    }
}
