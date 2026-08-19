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

package org.axonframework.common;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Represents a reference to a type of component, allowing for generic types to be specified without casting errors.
 *
 * @param <E> The type of the component.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public abstract class TypeReference<E> {

    protected final Type type;

    protected TypeReference() {
        Type superClass = this.getClass().getGenericSuperclass();
        if (superClass instanceof Class) {
            throw new IllegalArgumentException(
                    "Internal error: TypeReference constructed without actual type information");
        } else {
            this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
        }
    }

    private TypeReference(Type type) {
        this.type = Objects.requireNonNull(type, "The given type may not be null.");
    }

    /**
     * Constructs a {@code TypeReference} for the given {@code clazz}.
     *
     * @param clazz The clazz of the {@code TypeReference} under construction.
     * @param <C>   The clazz this {@code TypeReference} reflects.
     * @return A new {@code TypeReference} instance of the given {@code clazz}.
     */
    public static <C> TypeReference<C> fromClass(Class<C> clazz) {
        return new TypeReference<>(clazz) {
        };
    }

    /**
     * Constructs a {@code TypeReference} for the given {@code type}.
     *
     * @param type The type of the {@code TypeReference} under construction.
     * @param <C>  The type this {@code TypeReference} reflects.
     * @return A new {@code TypeReference} instance of the given {@code type}.
     */
    public static <C> TypeReference<C> fromType(Type type) {
        return new TypeReference<>(type) {
        };
    }

    /**
     * Returns the class of the type of the component represented by this {@code TypeReference}.
     * <p>
     * The class is the erasure of the represented type, following the erasure rules of the Java Language
     * Specification. A {@link Class} erases to itself, a {@link ParameterizedType} to its raw type, a
     * {@link java.lang.reflect.TypeVariable} to the erasure of its first bound, and a
     * {@link java.lang.reflect.GenericArrayType} to an array of the erasure of its component type. Any other
     * {@link Type} erases to {@link Object}.
     *
     * @return the class of the component
     */
    @SuppressWarnings("unchecked")
    public Class<E> getTypeAsClass() {
        return (Class<E>) TypeReflectionUtils.erase(type);
    }

    /**
     * Returns the type of the component represented by this {@code TypeReference}.
     *
     * @return The type of the component.
     */
    public Type getType() {
        return type;
    }

    /**
     * Checks if this {@code TypeReference} is assignable from the {@code other} {@code TypeReference}.
     * <p>
     * It takes generic parameters into account by first checking if the raw types match by means of
     * {@link Class#isAssignableFrom(Class)}, subsequently checking for compatibility of generic types by expecting an
     * exact match of the types if any of {@code this} and {@code other} represent a parameterized type.
     *
     * @param other the other {@code TypeReference} to compare this to
     * @return {@code true} if the {@code other} {@code TypeReference} is assignable from this {@code TypeReference}.
     */
    public boolean isAssignableFrom(TypeReference<?> other) {
        // check raw type compatibility
        if (!getTypeAsClass().isAssignableFrom(other.getTypeAsClass())) {
            return false;
        }
        // if both are parameterized types we attempt an exact match of the types
        // no subtype/supertype support for generic types at the moment
        if (type instanceof ParameterizedType && other.type instanceof ParameterizedType) {
            return type.equals(other.type);
        }
        // if any of the types is parameterized we would need to check in more detail to be sure
        // defensively returning false
        if (type instanceof ParameterizedType || other.type instanceof ParameterizedType) {
            return false;
        }
        // if both types are raw and compatible, we assume assignability
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TypeReference<?> that)) {
            return false;
        }
        return Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type);
    }
}
