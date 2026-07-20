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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.modelling.EntityEvolver;
import org.jspecify.annotations.Nullable;

/**
 * An {@link EntityEvolver} that can additionally expose the expected payload representation for handlers of a given
 * {@link QualifiedName}. This is useful for components that provide annotation-based usage of entities as it provides
 * information on the wanted Java class (the representation) by the user.
 *
 * @param <E> The entity type this evolver applies to.
 * @author Mitchell Herrijgers
 * @since 5.3.0
 */
public interface RepresentationResolvingEntityEvolver<E> extends EntityEvolver<E> {

    /**
     * Returns the {@link Class} of the expected representation for handlers of the given {@code qualifiedName}.
     *
     * @param qualifiedName The {@link QualifiedName} of the handler to look for.
     * @return The {@link Class} of the expected representation for handlers of the given {@code qualifiedName}, or
     * {@code null} if no such representation is found.
     */
    @Nullable
    Class<?> getExpectedRepresentation(QualifiedName qualifiedName);
}
