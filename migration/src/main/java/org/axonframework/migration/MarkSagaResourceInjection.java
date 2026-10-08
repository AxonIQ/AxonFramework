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
 * a {@code ResourceInjector}. Axon Framework 5 resolves those collaborators as parameters of the
 * {@code @SagaEventHandler} method instead. Which handlers need which collaborator is a decision this recipe does not
 * take, so it marks the two places that need manual work:
 * <ul>
 *     <li>an {@code @Autowired} or {@code @Inject} field of a Saga, being a class with a {@code @SagaEventHandler}
 *     method or the Spring {@code @Saga} stereotype;</li>
 *     <li>a class member, or a class declaration, that uses one of the Axon Framework 4 {@code ResourceInjector}
 *     types, or that calls {@code configureResourceInjector(..)} or {@code registerResourceInjector(..)}.</li>
 * </ul>
 * A {@code CommandGateway} field is not marked when it runs after
 * {@link MigrateCommandGatewayInSagaEventHandler}, which already replaces that field with a handler parameter. The
 * recipe is idempotent: a member that already carries the marker is left alone.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MarkSagaResourceInjection extends Recipe {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String SAGA_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Saga";
    private static final Set<String> INJECTION_ANNOTATION_FQNS = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "javax.inject.Inject",
            "jakarta.inject.Inject"
    );
    private static final Set<String> INJECTION_ANNOTATION_NAMES = Set.of("Autowired", "Inject");
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
            + " of each @SagaEventHandler method that uses it, then remove the field."
            + " Axon Framework 5 does not inject Saga fields.";
    private static final String INJECTOR_MARKER = "TODO(axon4to5): ResourceInjector is not ported.";
    private static final String INJECTOR_TODO = " " + INJECTOR_MARKER
            + " Remove it; Saga collaborators are resolved as @SagaEventHandler parameters.";

    @Override
    public String getDisplayName() {
        return "Mark resource injection in legacy Sagas";
    }

    @Override
    public String getDescription() {
        return "Leaves a `// TODO(axon4to5):` marker on `@Autowired` and `@Inject` fields of Sagas, and on usage of "
                + "the Axon Framework 4 `ResourceInjector` types. Axon Framework 5 does not inject Saga fields; it "
                + "resolves Saga collaborators as `@SagaEventHandler` method parameters instead. Idempotent.";
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
                    if (saga && member instanceof J.VariableDeclarations field && isInjected(field)) {
                        return mark(member, FIELD_MARKER, FIELD_TODO);
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

    private static boolean isInjected(J.VariableDeclarations field) {
        return field.getLeadingAnnotations().stream().anyMatch(annotation -> {
            String type = resolvedName(annotation.getType());
            return type != null
                    ? INJECTION_ANNOTATION_FQNS.contains(type)
                    : INJECTION_ANNOTATION_NAMES.contains(annotation.getSimpleName());
        });
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
