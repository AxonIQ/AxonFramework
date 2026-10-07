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
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Holder carrying the active, possibly {@code null}, entity state that is being evolved, so it can be injected as an
 * argument into a {@code static} {@code @EventHandler} method through the {@link ActiveEntityParameterResolver}.
 * <p>
 * A dedicated holder is used because {@link ProcessingContext} resources cannot be {@code null}, whereas the active
 * entity legitimately can be (representing an entity that does not exist yet, or that was removed). The presence of the
 * resource signals that an evolve step is in progress, while the wrapped value carries the actual, nullable state.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@Internal
final class ActiveEntity {

    static final Context.ResourceKey<ActiveEntity> RESOURCE_KEY =
            Context.ResourceKey.withLabel("AnnotationBasedEntityEvolvingComponent.activeEntity");

    private final @Nullable Object entity;

    private ActiveEntity(@Nullable Object entity) {
        this.entity = entity;
    }

    /**
     * Returns a copy of the given {@code context} carrying the given, possibly {@code null}, {@code entity} as the
     * active entity being evolved.
     *
     * @param context the context to enrich with the active entity
     * @param entity  the active entity being evolved, or {@code null} when the entity does not exist yet
     * @return a context carrying the active entity
     */
    static ProcessingContext set(ProcessingContext context, @Nullable Object entity) {
        return context.withResource(RESOURCE_KEY, new ActiveEntity(entity));
    }

    /**
     * Returns the active entity being evolved, or {@code null} when the entity does not exist yet.
     *
     * @return the active entity, or {@code null}
     */
    @Nullable
    Object entity() {
        return entity;
    }
}
