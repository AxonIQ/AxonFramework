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

package org.axonframework.modelling;

import java.util.List;

/**
 * Thrown when an {@link EntityIdResolver} was unable to determine an id from a given payload.
 *
 * @author John Hendrikx
 * @since 5.0.0
 */
public class EntityIdResolutionException extends Exception {

    /**
     * Constructs a free-form {@code EntityIdResolutionException} with the given {@code message}.
     *
     * @param message the message to add to the {@code EntityIdResolutionException} being constructed
     */
    public EntityIdResolutionException(String message) {
        super(message);
    }

    /**
     * Constructs a new instance.
     *
     * @param cls         the payload type, cannot be {@code null}
     * @param identifiers the extracted identifiers, cannot be {@code null}, or contain exactly 1 element
     * @throws IllegalArgumentException when any argument is {@code null}, or when {@code identifiers} contains exactly
     *                                  one element
     */
    public EntityIdResolutionException(Class<?> cls, List<Object> identifiers) {
        super(createMessage(cls, identifiers));
    }

    private static String createMessage(Class<?> cls, List<Object> identifiers) {
        if (identifiers == null || identifiers.size() == 1) {
            throw new IllegalArgumentException(
                    "identifiers cannot be null or contain exactly one element: " + identifiers
            );
        }

        return "Unable to resolve id for payload of type [%s]: %s".formatted(
                cls,
                identifiers.isEmpty() ? "found no identifiers" : "found multiple identifiers: %s".formatted(identifiers)
        );
    }
}
