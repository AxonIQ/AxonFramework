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

package org.axonframework.eventsourcing.commandhandling.annotation;

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ParameterResolverFactory} resolving the {@link EventCriteria} parameter of an {@link AppendCriteriaBuilder}
 * method to the criteria accumulated through sourcing for the command.
 * <p>
 * This factory is internal, as the framework picks it up from the classpath. The criteria are only known when the
 * events are appended, which is when {@link AppendCriteriaBuilderDefinition} invokes the method, so the parameter does
 * not take part in deciding whether the builder matches a command.
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
@Internal
public class SourcingCriteriaParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<EventCriteria> createInstance(Executable executable,
                                                           Parameter[] parameters,
                                                           int parameterIndex) {
        return parameters[parameterIndex].getType() == EventCriteria.class
                && AnnotationUtils.isAnnotationPresent(executable, AppendCriteriaBuilder.class)
                ? new SourcingCriteriaParameterResolver()
                : null;
    }

    private static final class SourcingCriteriaParameterResolver implements ParameterResolver<EventCriteria> {

        @Override
        public CompletableFuture<EventCriteria> resolveParameterValue(ProcessingContext context) {
            return CompletableFuture.completedFuture(
                    context.getResource(AppendCriteriaBuilderDefinition.SOURCING_CRITERIA)
            );
        }

        @Override
        public boolean matches(ProcessingContext context) {
            return true;
        }
    }
}
