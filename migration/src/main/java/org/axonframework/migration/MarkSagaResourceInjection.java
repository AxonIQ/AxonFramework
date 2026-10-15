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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.List;
import java.util.Set;

/**
 * Leaves a {@code // TODO(axon4to5):} marker wherever an Axon Framework 4 Saga relies on resource injection, which
 * the Axoniq Framework legacy module does not port.
 * <p>
 * Axon Framework 4 injected collaborators into {@code @Autowired} or {@code @Inject} fields of a Saga instance through
 * a {@code ResourceInjector}, called its {@code @Autowired} or {@code @Inject} methods, such as setters, and set its
 * Spring {@code @Value} fields. Axon Framework 5 resolves those collaborators as parameters of the
 * {@code @SagaEventHandler} or {@code @DeadlineHandler} method instead. Which handlers need which collaborator is a
 * decision this recipe does not take, so it marks the places that need manual work:
 * <ul>
 *     <li>an {@code @Autowired} or {@code @Inject} field or method of a Saga, being a class with a
 *     {@code @SagaEventHandler} method or the Spring {@code @Saga} stereotype;</li>
 *     <li>a Spring {@code @Value} field of a Saga, whose property a handler now reads itself;</li>
 *     <li>a class member, or a class declaration, that uses one of the Axon Framework 4 {@code ResourceInjector}
 *     types, or that calls {@code configureResourceInjector(..)} or {@code registerResourceInjector(..)}.</li>
 * </ul>
 * A {@code CommandGateway} field and its injection setter are not marked when it runs after
 * {@link MigrateCommandGatewayInSagaEventHandler}, which already replaces that field with a handler parameter, and
 * neither is a field {@link MoveSagaFieldToHandlerParameter} moved before, together with its injection setter. Without
 * a configured type, that recipe moves every field an injection setter assigns and a handler uses, so the setters left
 * to mark here belong to a field no handler uses, or to one that recipe leaves in place. The recipe is idempotent: a
 * member that already carries the marker is left alone.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MarkSagaResourceInjection extends Recipe {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String SAGA_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Saga";
    private static final String VALUE_FQN = "org.springframework.beans.factory.annotation.Value";
    private static final Set<String> RESOURCE_INJECTOR_FQNS = Set.of(
            "org.axonframework.modelling.saga.ResourceInjector",
            "org.axonframework.modelling.saga.AbstractResourceInjector",
            "org.axonframework.modelling.saga.SimpleResourceInjector",
            "org.axonframework.modelling.saga.repository.NoResourceInjector",
            "org.axonframework.config.ConfigurationResourceInjector",
            "org.axonframework.spring.saga.SpringResourceInjector",
            "org.axonframework.test.utils.AutowiredResourceInjector"
    );
    private static final Set<String> RESOURCE_INJECTOR_NAMES = Set.of(
            "ResourceInjector", "AbstractResourceInjector", "SimpleResourceInjector", "NoResourceInjector",
            "ConfigurationResourceInjector", "SpringResourceInjector", "AutowiredResourceInjector"
    );
    private static final Set<String> RESOURCE_INJECTOR_METHODS =
            Set.of("configureResourceInjector", "registerResourceInjector");

    private static final String FIELD_MARKER = "TODO(axon4to5): add this dependency as a parameter";
    private static final String FIELD_TODO = " " + FIELD_MARKER
            + " of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove the field."
            + " Axon Framework 5 does not inject Saga fields. Put a DeadlineMessage parameter first on a"
            + " @DeadlineHandler without parameters, or the dependency becomes its payload.";
    private static final String METHOD_MARKER = "TODO(axon4to5): add the dependency this method injects as a parameter";
    private static final String METHOD_TODO = " " + METHOD_MARKER
            + " of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove this method and the"
            + " field it sets. Axon Framework 5 does not call injection methods of a Saga. Put a DeadlineMessage"
            + " parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.";
    private static final String VALUE_MARKER = "TODO(axon4to5): read this property";
    private static final String VALUE_TODO = " " + VALUE_MARKER
            + " in each @SagaEventHandler or @DeadlineHandler method that uses it, from a Spring Environment"
            + " parameter or the configuration. Axon Framework 5 does not inject @Value Saga fields, so the field"
            + " no longer follows the property.";
    private static final String INJECTOR_MARKER = "TODO(axon4to5): ResourceInjector is not ported.";
    private static final String INJECTOR_TODO = " " + INJECTOR_MARKER
            + " Remove it; Saga collaborators are resolved as handler method parameters.";

    @Override
    public String getDisplayName() {
        return "Mark resource injection in legacy Sagas";
    }

    @Override
    public String getDescription() {
        return "Leaves a `// TODO(axon4to5):` marker on `@Autowired` and `@Inject` fields and methods of Sagas, such "
                + "as setters, on Spring `@Value` fields of Sagas, and on usage of the Axon Framework 4 "
                + "`ResourceInjector` types. Axon Framework 5 does not inject Saga fields or call Saga setters; it "
                + "resolves Saga collaborators as `@SagaEventHandler` and `@DeadlineHandler` method parameters "
                + "instead. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                // Nested classes are handled by the recursive call, before their enclosing class is marked.
                J.ClassDeclaration visited = super.visitClassDeclaration(classDeclaration, ctx);
                boolean saga = isSaga(visited);
                List<Statement> members = ListUtils.map(visited.getBody().getStatements(), member -> {
                    if (member instanceof J.ClassDeclaration) {
                        return member;
                    }
                    if (saga && member instanceof J.VariableDeclarations field) {
                        if (SagaInjection.isInjected(field.getLeadingAnnotations())) {
                            return mark(member, FIELD_MARKER, FIELD_TODO);
                        }
                        if (isValueInjected(field)) {
                            return mark(member, VALUE_MARKER, VALUE_TODO);
                        }
                    }
                    if (saga && member instanceof J.MethodDeclaration method
                            && SagaInjection.isInjectionMethod(method)) {
                        return mark(member, METHOD_MARKER, METHOD_TODO);
                    }
                    if (usesResourceInjector(member)) {
                        return mark(member, INJECTOR_MARKER, INJECTOR_TODO);
                    }
                    return member;
                });
                visited = visited.withBody(visited.getBody().withStatements(members));
                if (extendsResourceInjector(visited)) {
                    visited = mark(visited, INJECTOR_MARKER, INJECTOR_TODO);
                }
                return visited;
            }
        };
    }

    private static boolean isSaga(J.ClassDeclaration classDeclaration) {
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

    private static boolean isValueInjected(J.VariableDeclarations field) {
        return field.getLeadingAnnotations().stream()
                    .anyMatch(annotation -> isAnnotation(annotation, VALUE_FQN, "Value"));
    }

    private static boolean isAnnotation(J.Annotation annotation, String fullyQualifiedName, String simpleName) {
        String type = resolvedName(annotation.getType());
        return type != null ? fullyQualifiedName.equals(type) : simpleName.equals(annotation.getSimpleName());
    }

    private static boolean extendsResourceInjector(J.ClassDeclaration classDeclaration) {
        return (classDeclaration.getExtends() != null && usesResourceInjector(classDeclaration.getExtends()))
                || (classDeclaration.getImplements() != null
                && classDeclaration.getImplements().stream()
                                   .anyMatch(MarkSagaResourceInjection::usesResourceInjector));
    }

    private static boolean usesResourceInjector(Tree tree) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, Integer p) {
                // A local or nested class is marked on its own members.
                return classDeclaration;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (isResourceInjectorType(identifier)) {
                    found[0] = true;
                }
                return identifier;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if (RESOURCE_INJECTOR_METHODS.contains(invocation.getSimpleName())) {
                    found[0] = true;
                }
                return super.visitMethodInvocation(invocation, p);
            }
        }.visit(tree, 0);
        return found[0];
    }

    private static boolean isResourceInjectorType(J.Identifier identifier) {
        String type = resolvedName(identifier.getType());
        return type != null
                ? RESOURCE_INJECTOR_FQNS.contains(type)
                : RESOURCE_INJECTOR_NAMES.contains(identifier.getSimpleName());
    }

    /**
     * Returns the fully qualified name of a resolved type, or {@code null} when the type could not be resolved. The
     * callers fall back to the simple name only in that case, so a user type that happens to share a simple name with
     * an Axon type is not mistaken for it.
     */
    private static @Nullable String resolvedName(JavaType javaType) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(javaType);
        return type == null || type instanceof JavaType.Unknown ? null : type.getFullyQualifiedName();
    }

    /**
     * Prepends the TODO line comment to the element's prefix, keeping the element on its original indent below it.
     */
    private static <T extends J> T mark(T element, String marker, String todo) {
        Space prefix = element.getPrefix();
        for (Comment comment : prefix.getComments()) {
            if (comment instanceof TextComment text && text.getText().contains(marker)) {
                return element;
            }
        }
        String leading = prefix.getComments().isEmpty()
                ? prefix.getWhitespace()
                : prefix.getComments().getLast().getSuffix();
        String indent = leading.contains("\n") ? leading.substring(leading.lastIndexOf('\n') + 1) : "";
        TextComment comment = new TextComment(false, todo, "\n" + indent, Markers.EMPTY);
        return element.withPrefix(prefix.withComments(ListUtils.concat(prefix.getComments(), comment)));
    }
}
