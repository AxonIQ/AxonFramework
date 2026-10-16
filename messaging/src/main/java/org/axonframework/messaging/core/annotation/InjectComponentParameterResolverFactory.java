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
import org.axonframework.common.TypeReference;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.common.configuration.Configuration;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link ParameterResolverFactory} implementation that resolves parameters annotated with {@link InjectComponent}
 * to a component from the {@link Configuration} it was configured with.
 * <p>
 * Parameters without the {@link InjectComponent} annotation are ignored by this factory. For an annotated parameter,
 * the component is looked up by the parameter's type, including its type arguments, and, when given, the
 * {@link InjectComponent#name()}. When no matching component exists,
 * {@link #createInstance(Executable, Parameter[], int)} throws a {@link ComponentNotFoundException}, so a missing
 * component is reported when the handler is inspected.
 * <p>
 * This factory has a high {@link Priority}, ensuring an annotated parameter takes precedence over parameter resolvers
 * that resolve a value based on the parameter's type alone. It is registered automatically by the
 * {@link org.axonframework.messaging.core.configuration.reflection.ConfigurationParameterResolverConfigurationEnhancer}
 * when using the Configuration API.
 *
 * @author Allard Buijze
 * @see InjectComponent
 * @since 5.5.0
 */
@Priority(Priority.HIGHER)
public class InjectComponentParameterResolverFactory implements ParameterResolverFactory {

    private final Configuration configuration;

    /**
     * Initialize an instance using given {@code configuration} to look up the components to inject.
     *
     * @param configuration the configuration to look up components in
     */
    public InjectComponentParameterResolverFactory(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "The configuration cannot be null.");
    }

    @Nullable
    @Override
    public ParameterResolver<?> createInstance(Executable executable,
                                               Parameter[] parameters,
                                               int parameterIndex) {
        Parameter parameter = parameters[parameterIndex];
        Optional<String> annotatedName =
                AnnotationUtils.findAnnotationAttribute(parameter, InjectComponent.class, "name");
        if (annotatedName.isEmpty()) {
            return null;
        }
        String name = annotatedName.get().isEmpty() ? null : annotatedName.get();
        // A plain class keeps the class-based lookup, which also consults the registered ComponentFactories.
        // Generic types use a TypeReference, so their type arguments take part in the lookup.
        Type componentType = parameter.getParameterizedType();
        if (componentType instanceof Class<?> componentClass) {
            return configuration.getOptionalComponent(componentClass, name)
                                .map(FixedValueParameterResolver::new)
                                .orElseThrow(() -> new ComponentNotFoundException(componentClass, name));
        }
        TypeReference<?> componentTypeReference = TypeReference.fromType(componentType);
        return configuration.getOptionalComponent(componentTypeReference, name)
                            .map(FixedValueParameterResolver::new)
                            .orElseThrow(() -> new ComponentNotFoundException(componentTypeReference, name));
    }
}
