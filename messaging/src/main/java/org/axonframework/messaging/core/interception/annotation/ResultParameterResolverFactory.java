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

package org.axonframework.messaging.core.interception.annotation;

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ResourceOverridingProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * ParameterResolverFactory that provides support for Parameters where the result of Handler execution is expected to be
 * injected. This is only possible in interceptor handlers that need to act on the result of downstream interceptors or
 * the regular handler.
 * <p>
 * The {@link ResultHandler @ResultHandler} Meta-Annotation needs to be placed on handlers that support interacting with
 * the result type in its parameters.
 *
 * @author Allard Buijze
 * @since 4.4.0
 */
public class ResultParameterResolverFactory implements ParameterResolverFactory {


    private static final Object IGNORE_RESULT_PARAMETER_MARKER = new Object();
    private static final ResourceKey<Object> RESOURCE_KEY = ResourceKey.withLabel("Invocation result for interceptors");

    /**
     * Performs the given {@code action} while making the given {@code result} available for injection into handler
     * parameters annotated with {@link ResultHandler}.
     *
     * @param <R>               the type of result expected from the action
     * @param result            the result value to make available for parameter injection
     * @param processingContext the processing context to use when performing the action
     * @param action            the action to perform
     * @return the result returned by the given action
     */
    public static <R> R callWithResult(Object result,
                                       ProcessingContext processingContext,
                                       Function<ProcessingContext, R> action) {
        ProcessingContext wrapped = new ResourceOverridingProcessingContext<>(processingContext, RESOURCE_KEY, result);
        return action.apply(wrapped);
    }

    /**
     * Returns a {@link ProcessingContext} with the given {@code result} available for injection via
     * {@link ResultHandler}-annotated parameters. Use this when the caller needs to perform multiple operations
     * (e.g., {@code canHandle} followed by {@code handleSync}) with the same wrapped context.
     *
     * @param result  the result value to expose for parameter injection
     * @param context the base processing context to wrap
     * @return a processing context that exposes {@code result} to result-typed parameters
     */
    public static ProcessingContext withResult(Object result, ProcessingContext context) {
        return new ResourceOverridingProcessingContext<>(context, RESOURCE_KEY, result);
    }

    /**
     * Performs the given {@code action} ignoring any parameters expecting a result type. This is typically used to
     * detect whether a handler is suitable for invocation prior to the result value being available.
     *
     * @param <T> the type of result expected from the action
     * @param action            the action to perform
     * @param processingContext the {@link ProcessingContext} to use
     * @return the result returned by the given action
     */
    public static <T> T ignoringResultParameters(ProcessingContext processingContext,
                                                 Function<ProcessingContext, T> action) {
        ProcessingContext wrapped = new ResourceOverridingProcessingContext<>(processingContext, RESOURCE_KEY,
                                                                              IGNORE_RESULT_PARAMETER_MARKER);
        return action.apply(wrapped);
    }

    @Nullable
    @Override
    public ParameterResolver<Object> createInstance(Executable executable, Parameter[] parameters,
                                                    int parameterIndex) {
        if (Exception.class.isAssignableFrom(parameters[parameterIndex].getType())
                && AnnotationUtils.isAnnotationPresent(executable, ResultHandler.class)) {
            return new ExceptionResultParameterResolver(parameters[parameterIndex].getType());
        }
        return null;
    }

    private static class ExceptionResultParameterResolver implements ParameterResolver<Object> {

        private final Class<?> parameterType;

        private ExceptionResultParameterResolver(Class<?> resultType) {
            this.parameterType = resultType;
        }

        @Override
        public CompletableFuture<Object> resolveParameterValue(ProcessingContext context) {
            return CompletableFuture.completedFuture(context.getResource(RESOURCE_KEY));
        }

        @Override
        public boolean matches(ProcessingContext context) {
            Object registeredResult = context.getResource(RESOURCE_KEY);
            return IGNORE_RESULT_PARAMETER_MARKER.equals(registeredResult)
                    || parameterType.isInstance(registeredResult);
        }
    }
}
