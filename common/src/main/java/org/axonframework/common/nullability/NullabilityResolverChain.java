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

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.annotation.PriorityAnnotationComparator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Consults the {@link NullabilityResolver} instances found on the class path, falling back to reading a
 * {@code Nullable} annotation when none of them has an opinion.
 * <p>
 * Resolvers are located with the class loader of the class declaring the parameter under inspection, and cached
 * against that class. A provider that cannot be instantiated, typically because an optional dependency is absent, is
 * logged and skipped rather than failing the whole chain.
 * <p>
 * Kept package-private and separate from {@link NullabilityResolver} because an interface cannot hold the state this
 * requires. Callers reach this through {@link NullabilityResolver#nullabilityOf(Parameter)}.
 *
 * @author Mateusz Nowak
 * @see ServiceLoader
 * @since 5.3.0
 */
@Internal
final class NullabilityResolverChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(NullabilityResolverChain.class);
    private static final String NULLABLE = "nullable";

    /**
     * Caching against the declaring {@link Class} rather than its loader is what keeps this leak-free: the value is
     * reachable only from that class, which already references its own loader, so no reachability edge is added that
     * did not exist already, and the entry dies with the class.
     */
    private static final ClassValue<List<NullabilityResolver>> RESOLVERS = new ClassValue<>() {
        @Override
        protected List<NullabilityResolver> computeValue(Class<?> type) {
            return load(type.getClassLoader());
        }
    };

    private NullabilityResolverChain() {
        // not meant to be publicly instantiated
    }

    static Nullability resolve(Parameter parameter) {
        for (NullabilityResolver resolver : RESOLVERS.get(parameter.getDeclaringExecutable().getDeclaringClass())) {
            Nullability nullability = resolver.resolve(parameter);
            if (nullability != Nullability.UNKNOWN) {
                return nullability;
            }
        }
        return AnnotationUtils.hasAnnotationNamed(parameter, NULLABLE) ? Nullability.NULLABLE : Nullability.UNKNOWN;
    }

    /**
     * @param classLoader the loader to locate resolvers with, {@code null} for a class loaded by the bootstrap loader
     */
    private static List<NullabilityResolver> load(@Nullable ClassLoader classLoader) {
        Iterator<NullabilityResolver> iterator = ServiceLoader.load(NullabilityResolver.class, classLoader == null
                ? Thread.currentThread().getContextClassLoader()
                : classLoader).iterator();
        List<NullabilityResolver> resolvers = new ArrayList<>();
        while (iterator.hasNext()) {
            try {
                resolvers.add(iterator.next());
            } catch (ServiceConfigurationError | NoClassDefFoundError e) {
                LOGGER.info("NullabilityResolver instance ignored, as it relies on a class that is not available "
                                    + "on the classpath: {}", e.getMessage());
            }
        }
        resolvers.sort(PriorityAnnotationComparator.getInstance());
        return List.copyOf(resolvers);
    }
}
