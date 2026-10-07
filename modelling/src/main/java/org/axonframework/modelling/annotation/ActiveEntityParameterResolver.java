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

package org.axonframework.modelling.annotation;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;

/**
 * A {@link ParameterResolver} that injects the active, possibly {@code null}, entity state being evolved into a
 * {@code static} {@code @EventHandler} method. The value is read from the {@link ActiveEntity} holder placed on the
 * {@link ProcessingContext} by the {@link AnnotationBasedEntityEvolvingComponent} before invoking the handler.
 * <p>
 * The resolved value is {@code null} when the entity does not exist yet, allowing a static handler to create it from a
 * {@code null} state or to decide not to create it at all.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@Internal
class ActiveEntityParameterResolver implements ParameterResolver<Object> {

    @Override
    public CompletableFuture<Object> resolveParameterValue(ProcessingContext context) {
        ActiveEntity activeEntity = context.getResource(ActiveEntity.RESOURCE_KEY);
        return CompletableFuture.completedFuture(activeEntity == null ? null : activeEntity.entity());
    }

    @Override
    public boolean matches(ProcessingContext context) {
        return context.containsResource(ActiveEntity.RESOURCE_KEY);
    }
}
