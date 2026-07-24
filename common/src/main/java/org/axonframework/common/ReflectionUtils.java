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

import org.jspecify.annotations.Nullable;

import java.beans.Introspector;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.stream.StreamSupport.stream;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Utility class for working with Java Reflection API.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public final class ReflectionUtils {

    /**
     * A map of Primitive types to their respective wrapper types.
     */
    private static final Map<Type, Class<?>> primitiveWrapperTypeMap = new HashMap<>(8);
    private static final String UNSUPPORTED_MEMBER_TYPE_EXCEPTION_MESSAGE = "Unsupported member type [%s]";
    /**
     * Specifying a reflection operation should be performed recursive.
     */
    public static final boolean RECURSIVE = true;
    /**
     * Specifying a reflection operation should not be performed recursive.
     */
    public static final boolean NOT_RECURSIVE = false;

    static {
        primitiveWrapperTypeMap.put(boolean.class, Boolean.class);
        primitiveWrapperTypeMap.put(byte.class, Byte.class);
        primitiveWrapperTypeMap.put(char.class, Character.class);
        primitiveWrapperTypeMap.put(double.class, Double.class);
        primitiveWrapperTypeMap.put(float.class, Float.class);
        primitiveWrapperTypeMap.put(int.class, Integer.class);
        primitiveWrapperTypeMap.put(long.class, Long.class);
        primitiveWrapperTypeMap.put(short.class, Short.class);
    }

    /**
     * Returns the value of the given {@code field} in the given {@code object}.
     * <p>
     * If necessary, the field is made accessible, assuming the security manager allows it.
     *
     * @param field  The field containing the value.
     * @param object The object to retrieve the field's value from.
     * @param <R>    The type of the value to return.
     * @return The value of the {@code field} in the {@code object}.
     * @throws IllegalStateException If the field is not accessible and the security manager doesn't allow it to be made
     *                               accessible.
     */
    @SuppressWarnings("unchecked")
    public static <R> R getFieldValue(Field field, Object object) {
        ensureAccessible(field);
        try {
            return (R) field.get(object);
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            throw new IllegalStateException("Unable to access field for getting.", ex);
        }
    }

    public static <T> void setFieldValue(Field field, Object object, T value) {
        ensureAccessible(field);
        try {
            field.set(object, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Unable to access field for setting.", ex);
        }
    }

    /**
     * Returns the class on which the method with given {@code methodName} and parameters of type {@code parameterTypes}
     * is declared.
     * <p>
     * The given {@code instanceClass} is the instance on which the method can be called. If the method is not available
     * on the given {@code instanceClass}, {@code null} is returned.
     *
     * @param instanceClass  The class on which to look for the method.
     * @param methodName     The name of the method.
     * @param parameterTypes The parameter types of the method.
     * @return The class on which the method is declared, or {@code null} if not found.
     */
    public static @Nullable Class<?> declaringClass(Class<?> instanceClass,
                                                    String methodName,
                                                    Class<?>... parameterTypes) {
        try {
            return instanceClass.getMethod(methodName, parameterTypes).getDeclaringClass();
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Indicates whether the given class implements a customized equals method.
     * <p>
     * This method returns {@code true} if the declaring type of the equals method is not {@code Object}.
     *
     * @param type The type to inspect.
     * @return {@code true} if the given type overrides the equals method, otherwise {@code false}.
     */
    public static boolean hasEqualsMethod(Class<?> type) {
        return !Object.class.equals(declaringClass(type, "equals", Object.class));
    }

    /**
     * Indicates whether the two given objects are <em>not the same</em>, override an equals method that indicates they
     * are <em>not equal</em>, or implements {@link Comparable} which indicates the two are not equal.
     * <p>
     * If this method cannot safely indicate two objects are not equal, it returns {@code false}.
     *
     * @param value      One of the values to compare.
     * @param otherValue other value to compare.
     * @return {@code true} if these objects explicitly indicate they are not equal, {@code false} otherwise.
     */
    @SuppressWarnings("unchecked")
    public static boolean explicitlyUnequal(@Nullable Object value, @Nullable Object otherValue) {
        if (Objects.equals(value, otherValue)) {
            return false;
        } else if (value == null || otherValue == null) {
            return true;
        } else if (value instanceof Comparable) {
            //noinspection rawtypes
            return ((Comparable) value).compareTo(otherValue) != 0;
        } else if (hasEqualsMethod(value.getClass())) {
            return !value.equals(otherValue);
        }
        return false;
    }

    /**
     * Makes the given {@code member} accessible via reflection if it is not the case already.
     *
     * @param member The member (field, method, constructor, e.g.) to make accessible.
     * @param <T>    The type of member to make accessible.
     * @return The given {@code member}, for easier method chaining.
     * @throws IllegalStateException If the member is not accessible and the security manager doesn't allow it to be
     *                               made accessible.
     */
    public static <T extends AccessibleObject> T ensureAccessible(T member) {
        if (!isAccessible(member)) {
            member.setAccessible(true);
        }
        return member;
    }

    /**
     * Indicates whether the given {@code member} is accessible.
     * <p>
     * It does so by checking whether the member is non-final and public, or made accessible via reflection.
     *
     * @param member The member (field, method, constructor, e.g.) to check for accessibility
     * @return {@code true} if the member is accessible, otherwise {@code false}.
     */
    public static boolean isAccessible(AccessibleObject member) {
        return member instanceof Member m && isNonFinalPublicMember(m);
    }

    /**
     * Checks whether the given {@code member} is public and non-final.
     * <p>
     * These members do not need to be set accessible using reflection.
     *
     * @param member The member to check.
     * @return {@code true} if the member is public and non-final, otherwise {@code false}..
     * @see #isAccessible(java.lang.reflect.AccessibleObject)
     * @see #ensureAccessible(java.lang.reflect.AccessibleObject)
     */
    public static boolean isNonFinalPublicMember(Member member) {
        return (Modifier.isPublic(member.getModifiers())
                && Modifier.isPublic(member.getDeclaringClass().getModifiers())
                && !Modifier.isFinal(member.getModifiers()));
    }

    /**
     * Returns an {@link Iterable} of all the fields declared on the given class and its super classes.
     * <p>
     * The iterator will always return fields declared in a subtype before returning fields declared in a super type.
     *
     * @param clazz The class to return fields for.
     * @return An {@code Iterable} providing access to all declared fields in the class hierarchy.
     */
    public static Iterable<Field> fieldsOf(Class<?> clazz) {
        return fieldsOf(clazz, RECURSIVE);
    }

    /**
     * Returns an {@link Iterable} of all the fields declared on the given class.
     * <p>
     * Will include the given {@code clazz}' super classes if {@code recursive} has been set. The iterator will always
     * return fields declared in a subtype before returning fields declared in a super type.
     *
     * @param clazz     The class to return fields for.
     * @param recursive Defining whether fields should be found recursively on super classes as well.
     * @return An {@code Iterable} providing access to all declared fields in the class, including the hierarchy if
     * {@code recursive} was set.
     */
    public static Iterable<Field> fieldsOf(Class<?> clazz, boolean recursive) {
        List<Field> fields = new LinkedList<>();
        Class<?> currentClazz = clazz;
        do {
            fields.addAll(Arrays.asList(currentClazz.getDeclaredFields()));
            currentClazz = currentClazz.getSuperclass();
        } while (currentClazz != null && recursive);
        return Collections.unmodifiableList(fields);
    }

    /**
     * Utility function which returns a {@link Method} matching the given {@code methodName} and {@code parameterTypes}
     * in the {@code clazz}.
     *
     * @param clazz          The {@link java.lang.Class} to return a method for.
     * @param methodName     A {@link java.lang.String} for the simple name of the method to return.
     * @param parameterTypes An array of type {@link java.lang.Class} for all the parameters which are part  of the
     *                       {@link Method} being searched for.
     * @return A {@link Method} object from the given {@code clazz} matching the specified {@code methodName}.
     * @throws NoSuchMethodException If no {@link Method} can be found matching the {@code methodName} in
     *                               {@code clazz}.
     */
    static Method methodOf(Class<?> clazz,
                           String methodName,
                           Class<?>... parameterTypes) throws NoSuchMethodException {
        return clazz.getMethod(methodName, parameterTypes);
    }

    /**
     * Returns an {@link Iterable} of all the methods declared on the given class and its super classes.
     * <p>
     * The iterator will always return methods declared in a subtype before returning methods declared in a super type.
     *
     * @param clazz The class to return methods for.
     * @return An {@code Iterable} providing access to all declared methods in the class hierarchy.
     */
    public static Iterable<Method> methodsOf(Class<?> clazz) {
        return methodsOf(clazz, RECURSIVE);
    }

    /**
     * Returns an {@link Iterable} of all the methods declared on the given class.
     * <p>
     * Will include the given {@code clazz}' super classes if {@code recursive} has been set. The iterator will always
     * return fields declared in a subtype before returning fields declared in a super type.
     *
     * @param clazz     The class to return methods for.
     * @param recursive Defining whether methods should be found recursively on super classes as well.
     * @return An {@code Iterable} providing access to all declared methods in the class, including the hierarchy if
     * {@code recursive} was set.
     */
    public static Iterable<Method> methodsOf(Class<?> clazz, boolean recursive) {
        List<Method> methods = new LinkedList<>();
        Class<?> currentClazz = clazz;
        do {
            methods.addAll(Arrays.asList(currentClazz.getDeclaredMethods()));
            addMethodsOnDeclaredInterfaces(currentClazz, methods);
            currentClazz = currentClazz.getSuperclass();
        } while (currentClazz != null && recursive);
        return Collections.unmodifiableList(methods);
    }

    /**
     * Returns the boxed wrapper type for the given {@code primitiveType}.
     *
     * @param primitiveType The primitive type to return boxed wrapper type for.
     * @return The boxed wrapper type for the given {@code primitiveType}.
     * @throws IllegalArgumentException will be thrown instead of returning null if no wrapper class was found.
     */
    public static Class<?> resolvePrimitiveWrapperType(Class<?> primitiveType) {
        Assert.notNull(primitiveType, () -> "primitiveType may not be null");
        Assert.isTrue(primitiveType.isPrimitive(), () -> "primitiveType is not actually primitive: " + primitiveType);

        Class<?> primitiveWrapperType = primitiveWrapperTypeMap.get(primitiveType);
        Assert.notNull(primitiveWrapperType, () -> "no wrapper found for primitiveType: " + primitiveType);
        return primitiveWrapperType;
    }

    /**
     * Returns the boxed wrapper type for the given {@code type} if it is primitive.
     *
     * @param type A {@link Type} to return boxed wrapper type for.
     * @return The boxed wrapper type for the give {@code type}, or {@code type} if no wrapper class was found.
     */
    public static Type resolvePrimitiveWrapperTypeIfPrimitive(Type type) {
        Assert.notNull(type, () -> "type may not be null");
        return getOrDefault(primitiveWrapperTypeMap.get(type), type);
    }

    /**
     * Unwrap the given {@code type} if is wrapped by any of the given {@code wrapperTypes}.
     * <p>
     * This method assumes that the {@code wrapperTypes} have a single generic argument, which identifies the type they
     * wrap.
     * <p/>
     * For example, if invoked with {@code Future.class} and {@code Optional.class} as {@code wrapperTypes}:
     * <ul>
     * <li> {@code Future<String>} resolves to {@code String}</li>
     * <li> {@code Optional<String>} resolves to {@code String}</li>
     * <li> {@code Optional<Future<List<String>>>} resolves to {@code List<String>}</li>
     * </ul>
     *
     * @param type         The type to unwrap, if wrapped.
     * @param wrapperTypes The wrapper types to unwrap.
     * @return The unwrapped Type, or the original if it wasn't wrapped in any of the given wrapper types.
     */
    public static Type unwrapIfType(Type type, Class<?>... wrapperTypes) {
        for (Class<?> wrapperType : wrapperTypes) {
            Type wrapper = TypeReflectionUtils.getExactSuperType(type, wrapperType);
            if (wrapper instanceof ParameterizedType) {
                Type[] actualTypeArguments = ((ParameterizedType) wrapper).getActualTypeArguments();
                if (actualTypeArguments.length == 1) {
                    return unwrapIfType(actualTypeArguments[0], wrapperTypes);
                }
            } else if (wrapperType.equals(type)) {
                // the wrapper type doesn't declare what it wraps. In that case we just know it's an Object
                return Object.class;
            }
        }
        return type;
    }

    private static void addMethodsOnDeclaredInterfaces(Class<?> currentClazz, List<Method> methods) {
        for (Class<?> iface : currentClazz.getInterfaces()) {
            methods.addAll(Arrays.asList(iface.getDeclaredMethods()));
            addMethodsOnDeclaredInterfaces(iface, methods);
        }
    }

    /**
     * Indicates whether the given field has the "transient" modifier.
     *
     * @param field The field to inspect.
     * @return {@code true} if the field is marked transient, otherwise {@code false}.
     */
    public static boolean isTransient(Field field) {
        return Modifier.isTransient(field.getModifiers());
    }

    /**
     * Resolve a generic type parameter from a field declaration.
     *
     * @param field            The field to find generic parameters for.
     * @param genericTypeIndex The index of the type.
     * @return An optional that contains the resolved type, if found.
     */
    public static Optional<Class<?>> resolveGenericType(Field field, int genericTypeIndex) {
        final Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType)
                || ((ParameterizedType) genericType).getActualTypeArguments().length <= genericTypeIndex) {
            return Optional.empty();
        }
        return Optional.of((Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[genericTypeIndex]);
    }

    /**
     * Resolve a generic type parameter from a member declaration.
     *
     * @param member           The member to find generic parameters for.
     * @param genericTypeIndex The index of the type.
     * @return An optional that contains the resolved type, if found.
     */
    public static Optional<Class<?>> resolveMemberGenericType(Member member, int genericTypeIndex) {
        final Type genericType = getMemberGenericType(member);
        if (!(genericType instanceof ParameterizedType)
                || ((ParameterizedType) genericType).getActualTypeArguments().length <= genericTypeIndex) {
            return Optional.empty();
        }
        return Optional.of((Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[genericTypeIndex]);
    }

    /**
     * Invokes and returns the return value of the given {@code method} in the given {@code object}. If necessary, the
     * method is made accessible, assuming the security manager allows it.
     *
     * @param <R>    The type of return object from invoking the given {@code method}.
     * @param method The method to invoke.
     * @param object The target object the given {@code method} is invoked on.
     * @return The resulting value of invocation of the {@code method} in the {@code object}
     * @throws IllegalStateException If the method is not accessible and the security manager doesn't allow it to be
     *                               made accessible.
     */
    @SuppressWarnings("unchecked")
    public static <R> R invokeAndGetMethodValue(Method method, Object object) {
        ensureAccessible(method);
        try {
            return (R) method.invoke(object);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Unable to access method for invocation.", ex);
        }
    }

    /**
     * Returns the value of the given {@code member} in the given {@code object}, either by returning {@link Field}
     * value or invoking the method. If necessary, the member is made accessible, assuming the security manager allows
     * it. Supported members are {@link Field} and non-void {@link Method} without parameters.
     *
     * @param <R>    The type of return object from retrieving the {@code member's} value.
     * @param member The member containing or returning the value.
     * @param target The object to retrieve the member's value from.
     * @return The value of the {@code member} in the {@code object}.
     * @throws IllegalStateException if the member is not supported.
     */
    public static <R> R getMemberValue(Member member, Object target) {
        if (member instanceof Field field) {
            return ReflectionUtils.getFieldValue(field, target);
        } else if (member instanceof Method method) {
            return ReflectionUtils.invokeAndGetMethodValue(method, target);
        }
        throw new IllegalStateException(
                String.format(UNSUPPORTED_MEMBER_TYPE_EXCEPTION_MESSAGE, member.getClass().getName())
        );
    }

    /**
     * Returns the type of value of the given {@code member}, either by returning the type of {@link Field} or type of
     * the return value of a {@link Method}.
     *
     * @param member The member to get the value type from.
     * @return The type of value of the {@code member}.
     * @throws IllegalStateException If the member is not supported.
     */
    public static Class<?> getMemberValueType(Member member) {
        if (member instanceof Method method) {
            return method.getReturnType();
        } else if (member instanceof Field field) {
            return field.getType();
        }
        throw new IllegalStateException(
                String.format(UNSUPPORTED_MEMBER_TYPE_EXCEPTION_MESSAGE, member.getClass().getName())
        );
    }

    /**
     * Returns the generic type of value of the given {@code member}, either by returning the generic type of
     * {@link Field} or generic return type of {@link Method}.
     *
     * @param member The member to get generic type of.
     * @return The generic type of value of the {@code member}.
     * @throws IllegalStateException If the member is not supported.
     */
    static Type getMemberGenericType(Member member) {
        if (member instanceof Field field) {
            return field.getGenericType();
        } else if (member instanceof Method method) {
            return method.getGenericReturnType();
        }
        throw new IllegalStateException(
                String.format(UNSUPPORTED_MEMBER_TYPE_EXCEPTION_MESSAGE, member.getClass().getName())
        );
    }

    /**
     * Returns the generic string of the given {@code member}.
     *
     * @param member the member to get the generic string for.
     * @return The generic string of the {@code member}.
     * @throws IllegalStateException If the member is not supported.
     */
    public static String getMemberGenericString(Member member) {
        if (member instanceof Field field) {
            return field.toGenericString();
        } else if (member instanceof Executable executable) {
            return executable.toGenericString();
        }
        throw new IllegalStateException(
                String.format(UNSUPPORTED_MEMBER_TYPE_EXCEPTION_MESSAGE, member.getClass().getName())
        );
    }

    /**
     * Returns a discernible signature without including the classname.
     * <p>
     * This will contain the method name and the parameter types, such as:
     * {@code thisIfMyMethod(java.lang.String myString, com.acme.MyGreatObject)}.
     *
     * @param executable The executable to make a signature of.
     * @return The discernible signature.
     */
    public static String toDiscernibleSignature(Executable executable) {
        return String.format("%s(%s)",
                             executable.getName(),
                             Arrays.stream(executable.getParameterTypes()).map(Class::getName)
                                   .collect(Collectors.joining(",")));
    }

    /**
     * Returns the name of the field represented by the given {@code member}.
     * <p>
     * If the member is a method, it will return the name of the field it represents. For example, if the member is a
     * getter method for a field named "getFoo", "isFoo", or "foo", it will return "foo". If the member is a field, it
     * will return the name of the field.
     *
     * @param member The member to get the field name for.
     * @return The name of the field represented by the given member.
     */
    public static String fieldNameFromMember(Member member) {
        Objects.requireNonNull(member, "The member may not be null.");
        switch (member) {
            case Field field -> {
                return field.getName();
            }
            case Method method -> {
                String methodName = method.getName();
                int prefixLength;

                if (methodName.startsWith("get") || methodName.startsWith("set")) {
                    prefixLength = 3;
                } else if (methodName.startsWith("is")) {
                    prefixLength = 2;
                } else {
                    return methodName;
                }
                int length = methodName.length();
                if (length <= prefixLength) {
                    return methodName;
                }
                return Introspector.decapitalize(methodName.substring(prefixLength));
            }
            default -> throw new AxonConfigurationException(
                    format("Member [%s] is not a field or method", member)
            );
        }
    }

    /**
     * Each property of a record has both a backing {@link Field} and a {@link Method} (the accessor). This method
     * filters out the fields that have a corresponding method, as these would result in duplicate child entity
     * otherwise.
     *
     * @param methods The list of methods to check against the fields.
     * @return {@code true} if the field is not duplicated by a given method
     */
    private static Predicate<Field> deduplicateRecordFields(List<Method> methods) {
        return field -> methods
                .stream()
                .noneMatch(method -> method.getName().equals(field.getName())
                        && method.getParameterCount() == 0
                        && method.getReturnType().equals(field.getType()));
    }

    /**
     * Collects all {@link Member Members} of type {@link Field} and {@link Method} for given type in one single list.
     * If the given type is a record, the record-style getters are deduplicated.
     *
     * @param type The type which fields and methods are collected.
     * @return List of all found fields and messages.
     * @see #collectMatchingMethodsAndFields(Class, Predicate)
     */
    public static List<? extends Member> collectMethodsAndFields(Class<?> type) {
        return collectMatchingMethodsAndFields(type, it -> true);
    }

    /**
     * Collects all {@link Member Members} of type {@link Field} and {@link Method} which match the given filter for a
     * given type in one single list. If the given type is a record, the record-style getters are deduplicated.
     *
     * @param type   The type which fields and methods are collected.
     * @param filter A filter that only keeps matching members.
     * @return List of all found fields and messages.
     */
    public static List<? extends Member> collectMatchingMethodsAndFields(Class<?> type,
                                                                         Predicate<Member> filter) {
        List<Method> methods = stream(ReflectionUtils.methodsOf(type).spliterator(), false).toList();
        Predicate<Field> deduplicateFilter = type.isRecord() ? deduplicateRecordFields(methods) : field -> true;

        var fields = stream(ReflectionUtils.fieldsOf(type).spliterator(), false)
                .filter(deduplicateFilter);

        return Stream.concat(fields, methods.stream())
                     .filter(filter)
                     .toList();
    }

    /**
     * Returns a {@link List} of all enclosing classes of the given class.
     * <p>
     * The {@code List} returns enclosing classes from the immediate enclosing class outward to the outermost enclosing
     * class. If the given class has no enclosing class, an empty {@code List} is returned.
     *
     * @param clazz the class to return enclosing classes for
     * @return an immutable {@code List} providing access to all enclosing classes, from innermost to outermost
     */
    public static List<Class<?>> enclosingClassesOf(Class<?> clazz) {
        List<Class<?>> enclosingClasses = new ArrayList<>();
        Class<?> currentEnclosing = clazz.getEnclosingClass();
        while (currentEnclosing != null) {
            enclosingClasses.add(currentEnclosing);
            currentEnclosing = currentEnclosing.getEnclosingClass();
        }
        return Collections.unmodifiableList(enclosingClasses);
    }

    /**
     * Collects all concrete types from a sealed hierarchy if the given type is sealed. Returns an empty set if the type
     * is not sealed.
     *
     * @param rootType The root type to scan for sealed hierarchy.
     * @param <T>      The type parameter.
     * @return A set of all concrete types in the hierarchy, or an empty set if the type is not sealed.
     */
    public static <T> Set<Class<? extends T>> collectSealedHierarchyIfSealed(Class<T> rootType) {
        if (!rootType.isSealed()) {
            return Set.of();
        }
        Set<Class<? extends T>> result = new HashSet<>();
        collectSealedHierarchyRecursive(rootType, result);
        return result;
    }

    /**
     * Recursively collects all concrete types from a sealed hierarchy.
     *
     * @param type        The current type to scan.
     * @param accumulator The set to accumulate concrete types into.
     * @param <T>         The root type parameter.
     */
    @SuppressWarnings("unchecked")
    private static <T> void collectSealedHierarchyRecursive(Class<T> type,
                                                            Set<Class<? extends T>> accumulator) {
        if (type.isSealed()) {
            Class<?>[] permittedSubclasses = type.getPermittedSubclasses();
            if (permittedSubclasses != null) {
                for (Class<?> subclass : permittedSubclasses) {
                    // Recursively scan if the subclass is also sealed
                    if (subclass.isSealed()) {
                        collectSealedHierarchyRecursive((Class<T>) subclass, accumulator);
                    } else {
                        // Add concrete types to the accumulator
                        accumulator.add((Class<? extends T>) subclass);
                    }
                }
            }
        }
    }

    private ReflectionUtils() {
        // utility class
    }
}
