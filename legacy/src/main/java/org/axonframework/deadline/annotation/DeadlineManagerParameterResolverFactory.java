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

package org.axonframework.deadline.annotation;

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.deadline.CurrentDeadlineManager;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ParameterResolverFactory} that resolves a {@link DeadlineManager}-typed parameter, on a
 * {@link SagaEventHandler @SagaEventHandler} or {@link DeadlineHandler @DeadlineHandler} method, to the
 * {@link ProcessingContext}-scoped {@link DeadlineManager} registered under
 * {@link CurrentDeadlineManager#RESOURCE_KEY}.
 * <p>
 * Unlike {@code ScopeDescriptorParameterResolverFactory}, this factory does gate on the handler method's annotation:
 * {@code DeadlineManager} access is deliberately scoped to legacy {@code Saga} classes, the only components this
 * module keeps {@code @DeadlineHandler} support for. A {@code DeadlineManager}-typed parameter on any other handler
 * method (a plain {@code @CommandHandler} or {@code @EventHandler}, for instance) does not resolve.
 *
 * @author Jakob Hatzl
 * @since 5.4.0
 */
public class DeadlineManagerParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<DeadlineManager> createInstance(Executable executable,
                                                             Parameter[] parameters,
                                                             int parameterIndex) {
        if (!DeadlineManager.class.isAssignableFrom(parameters[parameterIndex].getType())
                || !(AnnotationUtils.isAnnotationPresent(executable, SagaEventHandler.class)
                     || AnnotationUtils.isAnnotationPresent(executable, DeadlineHandler.class))) {
            return null;
        }

        return new ParameterResolver<>() {
            @Override
            public CompletableFuture<DeadlineManager> resolveParameterValue(ProcessingContext context) {
                return CompletableFuture.completedFuture(CurrentDeadlineManager.forContext(context));
            }

            @Override
            public boolean matches(ProcessingContext context) {
                return true;
            }
        };
    }
}
