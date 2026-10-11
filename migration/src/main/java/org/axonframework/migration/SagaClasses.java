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

import java.util.Set;

/**
 * Tells Axon Framework 4 Saga classes apart from the other classes that declare deadline handlers.
 * <p>
 * A class is a Saga when it carries the Spring {@code @Saga} stereotype or declares a {@code @SagaEventHandler}
 * method. A Saga's {@code @DeadlineHandler} keeps working on the Axoniq Framework legacy module, while an aggregate's
 * one does not, so the deadline recipes skip Sagas. Since a Saga may split its handlers over a class hierarchy, the
 * scanning phase records every Saga together with its supertypes, and a class counts as a Saga when it, or one of its
 * supertypes, was recorded.
 * <p>
 * Package-private, as it only shares this detection between the deadline recipes of this module.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class SagaClasses {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String SAGA_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Saga";

    private SagaClasses() {
    }

    /**
     * Records the given class and all its supertypes in {@code sagaTypes} when it declares a Saga.
     *
     * @param classDeclaration the class to inspect
     * @param sagaTypes        the fully qualified names of the Saga classes and their supertypes found so far
     */
    static void collect(J.ClassDeclaration classDeclaration, Set<String> sagaTypes) {
        if (!declaresSaga(classDeclaration)) {
            return;
        }
        JavaType.FullyQualified type = classDeclaration.getType();
        while (type != null && !"java.lang.Object".equals(type.getFullyQualifiedName())) {
            sagaTypes.add(type.getFullyQualifiedName());
            type = type.getSupertype();
        }
    }

    /**
     * Returns whether the given class is a Saga, a supertype of one, or a subtype of one.
     *
     * @param classDeclaration the class to inspect
     * @param sagaTypes        the fully qualified names recorded by {@link #collect(J.ClassDeclaration, Set)}
     * @return {@code true} when the class takes part in a Saga
     */
    static boolean isSaga(J.ClassDeclaration classDeclaration, Set<String> sagaTypes) {
        if (declaresSaga(classDeclaration)) {
            return true;
        }
        JavaType.FullyQualified type = classDeclaration.getType();
        while (type != null && !(type instanceof JavaType.Unknown)) {
            if (sagaTypes.contains(type.getFullyQualifiedName())) {
                return true;
            }
            type = type.getSupertype();
        }
        return false;
    }

    private static boolean declaresSaga(J.ClassDeclaration classDeclaration) {
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
