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

package org.axonframework.modelling.saga;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * A resource injector that checks for @Inject annotated fields and setter methods to inject resources. If a field is
 * annotated with @Inject, a Resource of the type of that field is injected into it, if present. If
 * a method is annotated with @Inject, the method is invoked with a Resource of the type of the first parameter, if
 * present.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public class SimpleResourceInjector extends AbstractResourceInjector {

    private final Iterable<?> resources;

    /**
     * Initializes the resource injector to inject to given {@code resources}.
     *
     * @param resources The resources to inject
     */
    public SimpleResourceInjector(Object... resources) {
        this(Arrays.asList(resources));
    }

    /**
     * Initializes the resource injector to inject to given {@code resources}.
     * <p>
     * Note that any changes to the given collection will not be reflected by this injector.
     *
     * @param resources The resources to inject
     */
    public SimpleResourceInjector(Collection<?> resources) {
        this.resources = new ArrayList<>(resources);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <R> Optional<R> findResource(Class<R> requiredType) {
        return (Optional<R>) StreamSupport.stream(resources.spliterator(), false)
                .filter(requiredType::isInstance)
                .findFirst();
    }

}
