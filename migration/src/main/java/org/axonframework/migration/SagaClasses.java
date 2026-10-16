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

package org.axonframework.migration;

import org.jspecify.annotations.Nullable;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Tells Axon Framework 4 Saga classes apart from the other classes that declare deadline handlers.
 * <p>
 * A class declares a Saga when it carries the Spring {@code @Saga} stereotype or declares a {@code @SagaEventHandler}
 * method. A Saga's {@code @DeadlineHandler} keeps working on the Axoniq Framework legacy module, while an aggregate's
 * one does not, so the deadline recipes skip Sagas. Since a Saga may split its handlers over a class hierarchy, a class
 * counts as a Saga when it, or one of its supertypes, declares a Saga, and also when it is a supertype of a class that
 * declares one. Another subclass of such a supertype does not count: an aggregate may share a base class with a Saga.
 * <p>
 * The scanning phase of a recipe {@link #collect(J.ClassDeclaration) collects} every class, after which
 * {@link #isSaga(J.ClassDeclaration)} answers for any class. Package-private, as it only shares this detection between
 * the deadline recipes of this module.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class SagaClasses {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String SAGA_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Saga";

    private final Set<String> sagas = new HashSet<>();
    private final Set<String> sagaSupertypes = new HashSet<>();

    /**
     * Records the given class when it declares a Saga, together with its supertypes.
     *
     * @param classDeclaration the class to inspect
     */
    void collect(J.ClassDeclaration classDeclaration) {
        JavaType.FullyQualified type = classDeclaration.getType();
        if (type == null || !declaresSaga(classDeclaration)) {
            return;
        }
        sagas.add(type.getFullyQualifiedName());
        for (type = type.getSupertype(); isBelowObject(type); type = type.getSupertype()) {
            sagaSupertypes.add(type.getFullyQualifiedName());
        }
    }

    /**
     * Returns whether the given class is a Saga: it or one of its supertypes declares a Saga, or it is a supertype of
     * a class declaring one.
     *
     * @param classDeclaration the class to inspect
     * @return {@code true} when the class takes part in a Saga
     */
    boolean isSaga(J.ClassDeclaration classDeclaration) {
        if (declaresSaga(classDeclaration)) {
            return true;
        }
        JavaType.FullyQualified type = classDeclaration.getType();
        if (type != null && sagaSupertypes.contains(type.getFullyQualifiedName())) {
            return true;
        }
        for (; isBelowObject(type); type = type.getSupertype()) {
            if (sagas.contains(type.getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBelowObject(JavaType.@Nullable FullyQualified type) {
        return type != null
                && !(type instanceof JavaType.Unknown)
                && !"java.lang.Object".equals(type.getFullyQualifiedName());
    }

    /**
     * Returns whether the given class itself declares a Saga: it carries the Spring {@code @Saga} stereotype or
     * declares a {@code @SagaEventHandler} method. Unlike {@link #isSaga(J.ClassDeclaration)}, this needs no scanning
     * phase, so it suits a recipe that only looks at one class at a time.
     *
     * @param classDeclaration the class to inspect
     * @return {@code true} when the class declares a Saga
     */
    static boolean declaresSaga(J.ClassDeclaration classDeclaration) {
        if (classDeclaration.getLeadingAnnotations().stream()
                            .anyMatch(annotation -> isAnnotation(annotation, SAGA_STEREOTYPE_FQN, "Saga"))) {
            return true;
        }
        return classDeclaration.getBody().getStatements().stream()
                               .filter(J.MethodDeclaration.class::isInstance)
                               .map(J.MethodDeclaration.class::cast)
                               .flatMap(method -> method.getLeadingAnnotations().stream())
                               .anyMatch(annotation -> isAnnotation(annotation,
                                                                    SAGA_EVENT_HANDLER_FQN,
                                                                    "SagaEventHandler"));
    }

    /**
     * Returns whether the annotation is of the given type, falling back to its simple name only when its type could
     * not be resolved.
     *
     * @param annotation         the annotation to check
     * @param fullyQualifiedName the fully qualified name of the expected annotation type
     * @param simpleName         the simple name of the expected annotation type
     * @return {@code true} when the annotation is of the expected type
     */
    static boolean isAnnotation(J.Annotation annotation, String fullyQualifiedName, String simpleName) {
        String type = resolvedName(annotation.getType());
        return type != null ? fullyQualifiedName.equals(type) : simpleName.equals(annotation.getSimpleName());
    }

    /**
     * Returns the fully qualified name of a resolved type, or {@code null} when the type could not be resolved.
     *
     * @param javaType the type to name
     * @return the fully qualified name, or {@code null} for an unresolved type
     */
    static @Nullable String resolvedName(@Nullable JavaType javaType) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(javaType);
        return type == null || type instanceof JavaType.Unknown ? null : type.getFullyQualifiedName();
    }
}
