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

import org.axonframework.common.Priority;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;

/**
 * A {@link ParameterResolverFactory} implementation for simple resource injections. Uses the
 * {@link FixedValueParameterResolver} to inject a resource as a fixed value on message handling if the resource equals
 * a message handling method parameter.
 */
@Priority(Priority.LOWER)
public class SimpleResourceParameterResolverFactory implements ParameterResolverFactory {

    private final Iterable<?> resources;

    /**
     * Initialize the ParameterResolverFactory to inject the given {@code resource} in applicable parameters.
     *
     * @param resources The resource to inject
     */
    public SimpleResourceParameterResolverFactory(Iterable<?> resources) {
        this.resources = resources;
    }

    @Nullable
    @Override
    public ParameterResolver<?> createInstance(Executable executable,
                                               Parameter[] parameters,
                                               int parameterIndex) {
        for (Object resource : resources) {
            if (parameters[parameterIndex].getType().isInstance(resource)) {
                return new FixedValueParameterResolver<>(resource);
            }
        }
        return null;
    }
}
