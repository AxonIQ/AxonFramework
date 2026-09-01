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

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ParameterResolverFactory} that resolves the {@link SagaLifecycle} registered on the {@link ProcessingContext}
 * for any {@link SagaEventHandler @SagaEventHandler} method that declares a {@link SagaLifecycle}-typed parameter.
 *
 * @author Steven van Beelen
 * @since 5.4.0
 */
public class SagaLifecycleParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<SagaLifecycle> createInstance(Executable executable,
                                                           Parameter[] parameters,
                                                           int parameterIndex) {
        if (!SagaLifecycle.class.isAssignableFrom(parameters[parameterIndex].getType())
                || !AnnotationUtils.isAnnotationPresent(executable, SagaEventHandler.class)) {
            return null;
        }

        return new ParameterResolver<>() {
            @Override
            public CompletableFuture<SagaLifecycle> resolveParameterValue(ProcessingContext context) {
                return CompletableFuture.completedFuture(SagaLifecycle.forContext(context));
            }

            @Override
            public boolean matches(ProcessingContext context) {
                return context.containsResource(SagaLifecycle.RESOURCE_KEY);
            }
        };
    }
}
