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

package org.axonframework.common.configuration;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.TypeReference;

/**
 * A {@code RuntimeException} dedicated when a {@link Component} cannot be found in the {@link Configuration}.
 *
 * @author Steven van Beelen
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class ComponentNotFoundException extends RuntimeException {

    /**
     * Constructs a {@code ComponentNotFoundException} with a default message describing a {@link Component} couldn't be
     * found for the given {@code type} and {@code name}.
     *
     * @param type the type of the component that could not be found, typically an interface
     * @param name the name of the component that could not be found, potentially {@code null} when unimportant
     */
    public ComponentNotFoundException(Class<?> type, @Nullable String name) {
        this(type, name, null);
    }

    /**
     * Constructs a {@code ComponentNotFoundException} with a default message describing a {@link Component} couldn't be
     * found for the given {@code type} and {@code name}.
     *
     * @param type  the type of the component that could not be found, typically an interface
     * @param name  the name of the component that could not be found, potentially {@code null} when unimportant
     * @param cause the cause of this exception, potentially {@code null}
     * @since 5.2.0
     */
    public ComponentNotFoundException(Class<?> type, @Nullable String name, @Nullable Throwable cause) {
        super(exceptionMessageFor(type, name), cause);
    }

    /**
     * Constructs a {@code ComponentNotFoundException} with a default message describing a {@link Component} couldn't be
     * found for the given {@code typeReference} and {@code name}.
     *
     * @param typeReference the type of the component that could not be found, typically an interface
     * @param name          the name of the component that could not be found, potentially {@code null} when
     *                      unimportant
     */
    public ComponentNotFoundException(TypeReference<?> typeReference, @Nullable String name) {
        this(typeReference, name, null);
    }

    /**
     * Constructs a {@code ComponentNotFoundException} with a default message describing a {@link Component} couldn't be
     * found for the given {@code typeReference} and {@code name}.
     *
     * @param typeReference the type of the component that could not be found, typically an interface
     * @param name          the name of the component that could not be found, potentially {@code null} when
     *                      unimportant
     * @param cause         the cause of this exception, potentially {@code null}
     * @since 5.2.0
     */
    public ComponentNotFoundException(TypeReference<?> typeReference,
                                      @Nullable String name,
                                      @Nullable Throwable cause) {
        super(exceptionMessageFor(typeReference.getTypeAsClass(), name), cause);
    }

    private static String exceptionMessageFor(Class<?> type, @Nullable String name) {
        return name != null
                ? "No component found for type [" + type + "] name [" + name + "]."
                : "No component found for type [" + type + "].";
    }
}
