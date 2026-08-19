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

import org.axonframework.common.AxonNonTransientException;

/**
 * Exception indicating that an entity could not be found in, or recreated by, the {@link Repository}.
 * <p>
 * A missing entity is definitionally non-transient: retrying the same operation will not make the entity appear. As an
 * {@link AxonNonTransientException}, this is reflected in retry policies and dead-letter classification via
 * {@link org.axonframework.common.ExceptionUtils#isExplicitlyNonTransient(Throwable)}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 0.4.0
 */
public class EntityNotFoundException extends AxonNonTransientException {

    private final Object identifier;

    /**
     * Initialize an {@code EntityNotFoundException} for being unable to load an entity with the given
     * {@code identifier}.
     *
     * @param identifier the identifier of the entity that could not be found
     */
    public EntityNotFoundException(Object identifier) {
        super("No entity found for identifier [" + identifier + "].");
        this.identifier = identifier;
    }

    /**
     * Initialize an {@code EntityNotFoundException} for being unable to load an entity with the given
     * {@code identifier}, using the given {@code message} as the {@link RuntimeException#getMessage()}.
     *
     * @param identifier the identifier of the entity that could not be found
     * @param message    the message describing the cause of the exception
     */
    public EntityNotFoundException(Object identifier, String message) {
        super(message);
        this.identifier = identifier;
    }

    /**
     * Initialize an {@code EntityNotFoundException} for being unable to load an entity with the given
     * {@code identifier}, using the given {@code message} as the {@link RuntimeException#getMessage()} and the given
     * {@code cause} as the {@link RuntimeException#getCause()}.
     *
     * @param identifier the identifier of the entity that could not be found
     * @param message    the message describing the cause of the exception
     * @param cause      the underlying cause of the exception
     */
    public EntityNotFoundException(Object identifier, String message, Throwable cause) {
        super(message, cause);
        this.identifier = identifier;
    }

    /**
     * Returns the identifier of the entity that could not be found.
     *
     * @return the identifier of the entity that could not be found
     */
    public Object identifier() {
        return identifier;
    }
}
