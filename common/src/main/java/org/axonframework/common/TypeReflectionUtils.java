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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Utility class for doing reflection on types.
 * </p>
 * This TypeReflectionUtils is a trimmed down copy of the {@code com.googlecode.gentyref.GenericTypeReflector}.
 * All the unused functions have been removed and only the {@link TypeReflectionUtils#getExactSuperType(Type, Class)}
 * has remained. The private functions used by {@code getExactSuperType(Type, Class)} have also been tailored to our
 * exact needs.
 * </p>
 * The credits for creating this functionality however go to Wouter Coekaerts, which created this functionality in the
 * 'com.googlecode.gentyref' library with group id 'gentyref'.
 *
 * @author Wouter Coekaerts <wouter@coekaerts.be>
 * @author Steven van Beelen
 * @since 3.2
 */
public final class TypeReflectionUtils {

    private static final Logger logger = LoggerFactory.getLogger(TypeReflectionUtils.class);

    /**
     * Finds the most specific supertype of <tt>type</tt> whose erasure is <tt>searchClass</tt>.
     * In other words, returns a type representing the class <tt>searchClass</tt> plus its exact type parameters in
     * <tt>type</tt>.
     * <p>
     * <ul>
     * <li>Returns an instance of {@link ParameterizedType} if <tt>searchClass</tt> is a real class or interface and
     * <tt>type</tt> has parameters for it</li>
     * <li>Returns an instance of {@link GenericArrayType} if <tt>searchClass</tt> is an array type, and <tt>type</tt>
     * has type parameters for it</li>
     * <li>Returns an instance of {@link Class} if <tt>type</tt> is a raw type, or has no type parameters for
     * <tt>searchClass</tt></li>
     * <li>Returns null if <tt>searchClass</tt> is not a superclass of type.</li>
     * </ul>
     * <p>
     * <p>For example, with <tt>class StringList implements List&lt;String&gt;</tt>,
     * <tt>getExactSuperType(StringList.class, Collection.class)</tt>
     * returns a {@link ParameterizedType} representing <tt>Collection&lt;String&gt;</tt>.
     * </p>
     *
     * @param type        The type to search
     * @param searchClass The erased type of the super class to find
     * @return The supertype of {@code type}, whose erased type is {@code searchClass}
     */
    public static Type getExactSuperType(Type type, Class<?> searchClass) {
        if (type instanceof ParameterizedType || type instanceof Class || type instanceof GenericArrayType) {
            Class<?> clazz = erase(type);

            if (searchClass.equals(clazz)) {
                return type;
            }

            if (!searchClass.isAssignableFrom(clazz)) {
                return null;
            }
        }

        Type[] exactDirectSuperTypes = getExactDirectSuperTypes(type);
        for (Type superType : exactDirectSuperTypes) {
            Type result = getExactSuperType(superType, searchClass);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /**
     * Returns the erasure of the given {@code type}, following the erasure rules of the Java Language Specification.
     * <p>
     * A {@link Class} erases to itself, a {@link ParameterizedType} to its raw type, a {@link TypeVariable} to the
     * erasure of its first bound, and a {@link GenericArrayType} to an array of the erasure of its component type. Any
     * other {@link Type} erases to {@link Object}.
     * <p>
     * This method is package-private because it exposes the framework's implementation-specific type-erasure handling.
     *
     * @param type the type to return the erasure for
     * @return the erasure of the given {@code type}
     */
    static Class<?> erase(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        } else if (type instanceof TypeVariable) {
            TypeVariable<?> tv = (TypeVariable<?>) type;
            return tv.getBounds().length == 0 ? Object.class : erase(tv.getBounds()[0]);
        } else if (type instanceof GenericArrayType) {
            return Array.newInstance(erase(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        }
        logger.debug("{} is not supported for type erasure. Will by default return Object.", type.getClass());
        return Object.class;
    }

    /**
     * Returns the direct supertypes of the given type. Resolves type parameters.
     */
    private static Type[] getExactDirectSuperTypes(Type type) {
        if (type instanceof ParameterizedType || type instanceof Class) {
            return getExactDirectSuperTypesOfParameterizedTypeOrClass(type);
        } else if (type instanceof TypeVariable) {
            return ((TypeVariable<?>) type).getBounds();
        } else if (type instanceof WildcardType) {
            return ((WildcardType) type).getUpperBounds();
        } else if (type instanceof GenericArrayType) {
            return getExactDirectSuperTypes(((GenericArrayType) type).getGenericComponentType());
        } else if (type == null) {
            throw new IllegalArgumentException("Cannot handle given Type of null");
        }

        logger.debug("{} is not supported for retrieving the exact direct super types from. Will by default return the type contained in an Type[]", type.getClass());
        return new Type[]{type};
    }

    private static Type[] getExactDirectSuperTypesOfParameterizedTypeOrClass(Type type) {
        Class<?> clazz;
        if (type instanceof ParameterizedType) {
            clazz = (Class<?>) ((ParameterizedType) type).getRawType();
        } else {
            clazz = (Class<?>) type;
            if (clazz.isArray()) {
                Class<?> typeComponent = clazz.getComponentType();
                Type[] componentSupertypes = getExactDirectSuperTypes(typeComponent);
                Type[] result = new Type[componentSupertypes.length + 3];
                for (int resultIndex = 0; resultIndex < componentSupertypes.length; resultIndex++) {
                    result[resultIndex] = Array.newInstance((Class<?>) componentSupertypes[resultIndex], 0)
                            .getClass();
                }
                return result;
            }
        }

        Type[] superInterfaces = clazz.getGenericInterfaces();
        Type superClass = clazz.getGenericSuperclass();

        // The only supertype of an interface without superinterfaces is Object
        if (superClass == null && superInterfaces.length == 0 && clazz.isInterface()) {
            return new Type[]{Object.class};
        }

        Type[] result;
        int resultIndex;
        if (superClass == null) {
            result = new Type[superInterfaces.length];
            resultIndex = 0;
        } else {
            result = new Type[superInterfaces.length + 1];
            resultIndex = 1;
            result[0] = mapTypeParameters(superClass, type);
        }

        for (Type superInterface : superInterfaces) {
            result[resultIndex++] = mapTypeParameters(superInterface, type);
        }

        return result;
    }

    /**
     * Maps type parameters in a type to their values.
     *
     * @param toMapType     Type possibly containing type arguments
     * @param typeAndParams must be either ParameterizedType, or (in case there are no type arguments, or it's a raw type) Class
     * @return toMapType, but with type parameters from typeAndParams replaced.
     */
    private static Type mapTypeParameters(Type toMapType, Type typeAndParams) {
        if (isMissingTypeParameters(typeAndParams)) {
            return erase(toMapType);
        } else {
            VarMap varMap = new VarMap();
            Type handlingTypeAndParams = typeAndParams;
            while (handlingTypeAndParams instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType) handlingTypeAndParams;
                Class<?> rawType = (Class<?>) pType.getRawType();
                varMap.addAll(rawType.getTypeParameters(), pType.getActualTypeArguments());
                handlingTypeAndParams = pType.getOwnerType();
            }
            return varMap.map(toMapType);
        }
    }

    /**
     * Checks if the given type is a class that is supposed to have type parameters, but doesn't.
     * In other words, if it's a really raw type.
     */
    private static boolean isMissingTypeParameters(Type type) {
        if (type instanceof Class) {
            for (Class<?> clazz = (Class<?>) type; clazz != null; clazz = clazz.getEnclosingClass()) {
                if (clazz.getTypeParameters().length != 0) {
                    return true;
                }
            }
            return false;
        } else if (type instanceof ParameterizedType) {
            return false;
        } else {
            logger.debug("{} is not supported for checking if there are missing type parameters. Will by default return false.", type.getClass());
            return false;
        }
    }

    private TypeReflectionUtils() {
        // Utility class
    }

    /**
     * Mapping between type variables and actual parameters.
     *
     * @author Wouter Coekaerts <wouter@coekaerts.be>
     */
    private static class VarMap {

        private final Map<TypeVariable<?>, Type> map = new HashMap<>();

        /**
         * Creates an empty VarMap
         */
        VarMap() {
        }

        /**
         * Creates a VarMap mapping the type parameters of the class used in <tt>type</tt> to their actual value.
         */
        private void addAll(TypeVariable<?>[] variables, Type[] values) {
            assert variables.length == values.length;
            IntStream.range(0, variables.length).forEach(i -> map.put(variables[i], values[i]));
        }

        private Type map(Type type) {
            if (type instanceof Class) {
                return type;
            } else if (type instanceof TypeVariable) {
                if (!map.containsKey(type)) {
                    throw new UnresolvedTypeVariableException((TypeVariable<?>) type);
                }
                return map.get(type);
            } else if (type instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType) type;
                Type[] actualTypeArguments = map(pType.getActualTypeArguments());
                Type ownerType = pType.getOwnerType() == null ? pType.getOwnerType() : map(pType.getOwnerType());
                return new ParameterizedTypeImpl((Class<?>) pType.getRawType(), actualTypeArguments, ownerType);
            }

            logger.debug("{} is not supported for variable mapping. Will by default return the type as is.", type.getClass());
            return type;
        }

        private Type[] map(Type[] types) {
            return Arrays.stream(types).map(this::map).toArray(Type[]::new);
        }

        private static class UnresolvedTypeVariableException extends RuntimeException {

            UnresolvedTypeVariableException(TypeVariable<?> tv) {
                super("An exact type is requested, but the type contains a type variable that cannot be resolved.\n   "
                              + "Variable: " + tv.getName() + " from " + tv.getGenericDeclaration() + "\n   "
                              + "Hint: This is usually caused by trying to get an exact type when a generic method "
                              + "who's type parameters are not given is involved.");
            }
        }

        private static class ParameterizedTypeImpl implements ParameterizedType {

            private final Class<?> rawType;
            private final Type[] actualTypeArguments;
            private final Type ownerType;

            ParameterizedTypeImpl(Class<?> rawType, Type[] actualTypeArguments, Type ownerType) {
                this.rawType = rawType;
                this.actualTypeArguments = actualTypeArguments;
                this.ownerType = ownerType;
            }

            public Type getRawType() {
                return rawType;
            }

            public Type[] getActualTypeArguments() {
                return actualTypeArguments;
            }

            public Type getOwnerType() {
                return ownerType;
            }
        }
    }
}
