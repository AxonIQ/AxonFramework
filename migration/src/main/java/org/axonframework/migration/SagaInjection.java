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
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.List;
import java.util.Set;

/**
 * Recognizes the Saga members through which Axon Framework 4 injected collaborators: {@code @Autowired} or
 * {@code @Inject} fields and methods.
 * <p>
 * Axon Framework 4 populated a Saga instance through a {@code ResourceInjector}, which set injected fields and called
 * injected methods, typically setters. The Axoniq Framework legacy module does neither, so the Saga recipes move such a
 * collaborator into a handler parameter, or mark the member for manual migration. An injection setter, being an
 * injected method that only assigns its single parameter to a field, is handled like an injected field.
 * <p>
 * Package-private, as it only shares this detection between the Saga recipes of this module.
 *
 * @author Stefan Dragisic
 * @since 5.4.1
 */
final class SagaInjection {

    private static final Set<String> INJECTION_ANNOTATION_FQNS = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "javax.inject.Inject",
            "jakarta.inject.Inject"
    );
    private static final Set<String> INJECTION_ANNOTATION_NAMES = Set.of("Autowired", "Inject");

    private SagaInjection() {
    }

    /**
     * Returns whether one of the given annotations is {@code @Autowired} or {@code @Inject}, matching on the simple
     * name only when the annotation type could not be resolved.
     *
     * @param annotations the leading annotations of a field or method
     * @return {@code true} when the member is injected
     */
    static boolean isInjected(List<J.Annotation> annotations) {
        return annotations.stream().anyMatch(annotation -> {
            String type = SagaClasses.resolvedName(annotation.getType());
            return type != null
                    ? INJECTION_ANNOTATION_FQNS.contains(type)
                    : INJECTION_ANNOTATION_NAMES.contains(annotation.getSimpleName());
        });
    }

    /**
     * Returns whether the given method is an injected method, being an {@code @Autowired} or {@code @Inject} method
     * that is not a constructor.
     *
     * @param method the method declaration to inspect
     * @return {@code true} when the method is an injected method
     */
    static boolean isInjectionMethod(J.MethodDeclaration method) {
        return isInjected(method.getLeadingAnnotations()) && !isConstructor(method);
    }

    /**
     * Returns the name of the field the given method injects, when it is an injection setter: an injected instance
     * method with a single parameter whose body only assigns that parameter to a field, as {@code this.field = value}
     * or {@code field = value}.
     *
     * @param method the method declaration to inspect
     * @return the name of the assigned field, or {@code null} when the method is no injection setter
     */
    static @Nullable String setterField(J.MethodDeclaration method) {
        if (!isInjectionMethod(method) || method.hasModifier(J.Modifier.Type.Static) || method.getBody() == null) {
            return null;
        }
        List<Statement> parameters = method.getParameters();
        List<Statement> statements = method.getBody().getStatements();
        if (parameters.size() != 1
                || !(parameters.getFirst() instanceof J.VariableDeclarations parameter)
                || parameter.getVariables().size() != 1
                || statements.size() != 1
                || !(statements.getFirst() instanceof J.Assignment assignment)) {
            return null;
        }
        String parameterName = parameter.getVariables().getFirst().getSimpleName();
        if (!(assignment.getAssignment() instanceof J.Identifier value)
                || !value.getSimpleName().equals(parameterName)) {
            return null;
        }
        Expression variable = assignment.getVariable();
        if (variable instanceof J.FieldAccess access
                && access.getTarget() instanceof J.Identifier target
                && target.getSimpleName().equals("this")) {
            return access.getSimpleName();
        }
        if (variable instanceof J.Identifier identifier && !identifier.getSimpleName().equals(parameterName)) {
            return identifier.getSimpleName();
        }
        return null;
    }

    /**
     * Kotlin functions without a declared return type look like constructors to
     * {@link J.MethodDeclaration#isConstructor()}, so the attributed method type decides when there is one.
     */
    private static boolean isConstructor(J.MethodDeclaration method) {
        JavaType.Method type = method.getMethodType();
        if (type != null) {
            return type.isConstructor();
        }
        String name = method.getSimpleName();
        return name.equals("constructor")
                || (method.isConstructor() && !name.isEmpty() && Character.isUpperCase(name.charAt(0)));
    }
}
