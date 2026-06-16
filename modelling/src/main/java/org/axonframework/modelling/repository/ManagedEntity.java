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

package org.axonframework.modelling.repository;

import org.jspecify.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * A wrapper around an entity whose lifecycle is being managed by an {@link Repository}.
 *
 * @param <ID> The type of identifier of the entity.
 * @param <E>  The type of the entity.
 * @author Allard Buijze
 * @since 5.0.0
 */
public interface ManagedEntity<ID, E> {

    /**
     * The identifier of the entity.
     *
     * @return The identifier of the entity.
     */
    ID identifier();

    /**
     * The current state of the entity, {@code null} when nothing was loaded from the repository.
     *
     * @return the current state of the entity
     */
    @Nullable
    E entity();

    /**
     * Change the current state of the entity using the given {code change} function.
     *
     * @param change The function applying the requested change.
     * @return The state of the entity after the change.
     */
    E applyStateChange(UnaryOperator<E> change);
}
