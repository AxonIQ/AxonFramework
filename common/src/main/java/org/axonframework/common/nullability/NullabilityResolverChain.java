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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import static java.util.ServiceLoader.load;

/**
 * Locates {@link NullabilityResolver} instances on the class path using the {@link ServiceLoader} mechanism, and
 * consults them in descending {@link NullabilityResolver#priority()} order.
 * <p>
 * Resolvers are located with the class loader of the class declaring the parameter under inspection, falling back to
 * the thread context class loader, so that a resolver shipped alongside the inspected code is found. A provider that
 * cannot be instantiated, typically because an optional dependency is absent, is logged and skipped rather than
 * failing the whole chain.
 * <p>
 * Kept package-private and separate from {@link NullabilityResolver} because an interface cannot hold the logic this
 * requires. Callers reach this through {@link NullabilityResolver#nullabilityOf(Parameter)}.
 * <p>
 * Marked {@link Internal} because it exists purely to serve that call, and carries no contract of its own.
 *
 * @author Mateusz Nowak
 * @see ServiceLoader
 * @since 5.3.0
 */
@Internal
final class NullabilityResolverChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(NullabilityResolverChain.class);

    private NullabilityResolverChain() {
        // not meant to be publicly instantiated
    }

    static Nullability resolve(Parameter parameter) {
        Class<?> declaringClass = parameter.getDeclaringExecutable().getDeclaringClass();
        for (NullabilityResolver resolver : resolversFor(declaringClass.getClassLoader())) {
            Nullability nullability = resolver.resolve(parameter);
            if (nullability != Nullability.UNKNOWN) {
                return nullability;
            }
        }
        return Nullability.UNKNOWN;
    }

    /**
     * @param classLoader the loader to locate resolvers with, {@code null} for a class loaded by the bootstrap loader
     */
    private static List<NullabilityResolver> resolversFor(@Nullable ClassLoader classLoader) {
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
        return resolvers;
    }
}
