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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.eventsourcing.eventstore.EventStoreAppender;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ParameterResolverFactory} that ensures the {@link EventStoreAppender} is resolved in the context of the
 * current {@link ProcessingContext}.
 * <p>
 * For any message handler that declares this parameter, it will call
 * {@link EventStoreAppender#forContext(ProcessingContext)} to create the appender. Only matches the exact
 * {@link EventStoreAppender} type, leaving the resolution of its supertype
 * {@link org.axonframework.messaging.eventhandling.gateway.EventAppender} to
 * {@link org.axonframework.messaging.eventhandling.annotation.EventAppenderParameterResolverFactory}.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public class EventStoreAppenderParameterResolverFactory implements ParameterResolverFactory {

    @Nullable
    @Override
    public ParameterResolver<EventStoreAppender> createInstance(
            Executable executable,
            Parameter[] parameters,
            int parameterIndex
    ) {
        if (parameters[parameterIndex].getType() == EventStoreAppender.class) {
            return new ParameterResolver<>() {
                @Override
                public CompletableFuture<EventStoreAppender> resolveParameterValue(ProcessingContext context) {
                    return CompletableFuture.completedFuture(EventStoreAppender.forContext(context));
                }

                @Override
                public boolean matches(ProcessingContext context) {
                    return true;
                }
            };
        }
        return null;
    }
}
