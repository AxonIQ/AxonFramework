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

import org.axonframework.messaging.core.CurrentScope;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ParameterResolverFactory} that resolves a {@link ScopeDescriptor}-typed parameter on any handler method to
 * the value of {@link CurrentScope#describeCurrentScope(ProcessingContext)}.
 * <p>
 * Unlike, for example, {@code SagaLifecycleParameterResolverFactory}, this factory places no restriction on which
 * handler methods it applies to: a {@link ScopeDescriptor}-typed parameter resolves on any handler method
 * declaring one, matching the Axon Framework 4 behaviour this factory replaces. This is deliberate, not an
 * oversight -- a Saga's {@code @DeadlineHandler} method is not itself meta-annotated {@code @SagaEventHandler}, so
 * gating on that annotation here would leave such a method unable to resolve a {@link ScopeDescriptor} parameter at
 * all.
 *
 * @author Jakob Hatzl
 * @since 5.4.0
 */
public class ScopeDescriptorParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<ScopeDescriptor> createInstance(Executable executable,
                                                             Parameter[] parameters,
                                                             int parameterIndex) {
        if (!ScopeDescriptor.class.isAssignableFrom(parameters[parameterIndex].getType())) {
            return null;
        }

        return new ParameterResolver<>() {
            @Override
            public CompletableFuture<ScopeDescriptor> resolveParameterValue(ProcessingContext context) {
                return CompletableFuture.completedFuture(CurrentScope.describeCurrentScope(context));
            }

            @Override
            public boolean matches(ProcessingContext context) {
                return true;
            }
        };
    }
}
