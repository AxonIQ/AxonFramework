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

import org.axonframework.messaging.core.NoScopeDescriptor;
import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * Factory for a {@link ScopeDescriptor} {@link ParameterResolver}. Will return the result of
 * {@link Scope#describeCurrentScope()}. If no current scope is active, {@link NoScopeDescriptor#INSTANCE} will be
 * returned.
 *
 * @author Steven van Beelen
 * @since 4.5
 */
public class ScopeDescriptorParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<ScopeDescriptor> createInstance(Executable executable,
                                                             Parameter[] parameters,
                                                             int parameterIndex) {
        return ScopeDescriptor.class.isAssignableFrom(parameters[parameterIndex].getType())
                ? new ScopeDescriptorParameterResolver() : null;
    }

    private static class ScopeDescriptorParameterResolver implements ParameterResolver<ScopeDescriptor> {

        @Override
        public CompletableFuture<ScopeDescriptor> resolveParameterValue(ProcessingContext context) {
            try {
                return CompletableFuture.completedFuture(Scope.describeCurrentScope());
            } catch (IllegalStateException e) {
                return CompletableFuture.completedFuture(NoScopeDescriptor.INSTANCE);
            }
        }

        @Override
        public boolean matches(ProcessingContext context) {
            return true;
        }
    }
}
