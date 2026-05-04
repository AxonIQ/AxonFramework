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

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans for methods annotated with {@code @CommandHandler} and annotates their command parameter
 * types with {@code @Command}.
 * <p>
 * If the command class had a field — or a Java {@code record} component, or a Kotlin
 * {@code data class} primary constructor parameter — annotated with {@code @RoutingKey}, that
 * annotation is removed and replaced with {@code @Command(routingKey = "fieldName")} on the class,
 * matching the AF5 routing-key contract where the routing key is declared on the command class
 * itself. Java records and class fields complete the lift in a single OpenRewrite cycle: the
 * recipe walks {@code J.ClassDeclaration.primaryConstructor} (records) and
 * {@code J.ClassDeclaration.body} (fields) directly, and crucially performs the
 * {@code @RoutingKey} removal AFTER {@code JavaTemplate.apply} adds the class-level
 * {@code @Command} — {@code JavaTemplate.apply} walks the visitor's cursor (still pointing at
 * the un-modified class declaration) and would otherwise discard any child mutations applied
 * beforehand. Kotlin {@code data class} primary-constructor parameters live outside
 * {@code J.ClassDeclaration} (the Kotlin parser keeps them on a sibling Kotlin LST node), so
 * for Kotlin the recipe falls back to the {@code visitVariableDeclarations} hook to capture
 * the parameter name and to strip the now-orphaned annotation in a second cycle.
 * <p>
 * Both AF4 ({@code org.axonframework.commandhandling.CommandHandler}) and AF5
 * ({@code org.axonframework.messaging.commandhandling.annotation.CommandHandler}) FQNs are matched
 * so the recipe is safe to run before or after {@code Axon4ToAxon5Messaging}.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class AddCommandAnnotation extends ScanningRecipe<AddCommandAnnotation.Accumulator> {

    private static final String COMMAND_HANDLER_AF4 = "org.axonframework.commandhandling.CommandHandler";
    private static final String COMMAND_HANDLER_AF5 = "org.axonframework.messaging.commandhandling.annotation.CommandHandler";
    private static final String COMMAND_FQN = "org.axonframework.messaging.commandhandling.annotation.Command";
    private static final String ROUTING_KEY_AF4 = "org.axonframework.commandhandling.RoutingKey";
    private static final String ROUTING_KEY_AF5 = "org.axonframework.messaging.commandhandling.RoutingKey";
    private static final String ROUTING_KEY_FIELD_MESSAGE = "axon4to5.routingKeyField";

    public static class Accumulator {

        final Set<String> commandTypeFqns = new HashSet<>();
    }

    @Override
    public String getDisplayName() {
        return "Add @Command to command payload classes";
    }

    @Override
    public String getDescription() {
        return "Scans @CommandHandler methods and annotates their command parameter types with "
                + "@Command. Also migrates @RoutingKey on a field to @Command(routingKey = \"fieldName\") "
                + "on the class, removing the @RoutingKey field annotation.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (isCommandHandler(method)) {
                    List<Statement> params = method.getParameters();
                    if (!params.isEmpty() && params.get(0) instanceof J.VariableDeclarations) {
                        J.VariableDeclarations firstParam = (J.VariableDeclarations) params.get(0);
                        if (firstParam.getTypeExpression() != null) {
                            JavaType.FullyQualified fqType = TypeUtils.asFullyQualified(
                                    firstParam.getTypeExpression().getType());
                            if (fqType != null
                                    && !fqType.getFullyQualifiedName().startsWith("org.axonframework")) {
                                acc.commandTypeFqns.add(fqType.getFullyQualifiedName());
                            }
                        }
                    }
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            private boolean isCommandHandler(J.MethodDeclaration method) {
                for (J.Annotation ann : method.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), COMMAND_HANDLER_AF4)
                            || TypeUtils.isOfClassType(ann.getType(), COMMAND_HANDLER_AF5)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                            ExecutionContext ctx) {
                if (classDecl.getType() == null
                        || !acc.commandTypeFqns.contains(classDecl.getType().getFullyQualifiedName())
                        || hasAnnotation(classDecl, COMMAND_FQN)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                // Find the routing-key field/component name BEFORE adding @Command. Direct walk
                // covers Java record components (in `getPrimaryConstructor()`) and class body
                // fields. For Kotlin data classes, the primary-constructor parameters live
                // outside `J.ClassDeclaration` on a sibling Kotlin LST node, so we fall back to
                // a super-visit that runs the `visitVariableDeclarations` hook below — that
                // hook writes the captured name onto this cursor's message bus.
                String routingKeyField = findRoutingKeyFieldName(classDecl);
                if (routingKeyField == null) {
                    super.visitClassDeclaration(classDecl, ctx);
                    routingKeyField = getCursor().getMessage(ROUTING_KEY_FIELD_MESSAGE);
                }

                String annotationText = routingKeyField != null
                        ? "@Command(routingKey = \"" + routingKeyField + "\")"
                        : "@Command";

                // Add the class-level @Command annotation FIRST. JavaTemplate.apply walks this
                // visitor's cursor (which still references the un-modified `classDecl`), so any
                // child mutations applied beforehand would be silently discarded by the apply.
                J.ClassDeclaration annotated = JavaTemplate.builder(annotationText)
                        .imports(COMMAND_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), classDecl.getCoordinates().addAnnotation((a, b) -> 0));
                maybeAddImport(COMMAND_FQN, null, false);

                // Now strip @RoutingKey from record components and body fields on the
                // already-annotated class. Operating on `annotated` (the apply output) is safe —
                // these mutations are returned directly from the visitor and the framework wires
                // them into the parent compilation unit.
                if (routingKeyField != null) {
                    annotated = removeRoutingKeyFromComponentsAndFields(annotated);
                    maybeRemoveImport(ROUTING_KEY_AF4);
                    maybeRemoveImport(ROUTING_KEY_AF5);
                }

                return super.visitClassDeclaration(annotated, ctx);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar,
                                                                     ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVar, ctx);
                J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosingClass == null || enclosingClass.getType() == null
                        || !acc.commandTypeFqns.contains(
                                enclosingClass.getType().getFullyQualifiedName())) {
                    return vd;
                }
                if (!hasRoutingKeyAnnotation(vd)) {
                    return vd;
                }
                // Kotlin fallback: publish the captured parameter name so the enclosing
                // visitClassDeclaration can pull it from the cursor message bus when its direct
                // walk through `J.ClassDeclaration` returned nothing.
                if (!vd.getVariables().isEmpty()) {
                    String name = vd.getVariables().get(0).getSimpleName();
                    Cursor enclosingClassCursor = getCursor()
                            .dropParentUntil(it -> it instanceof J.ClassDeclaration);
                    enclosingClassCursor.putMessage(ROUTING_KEY_FIELD_MESSAGE, name);
                }
                maybeRemoveImport(ROUTING_KEY_AF4);
                maybeRemoveImport(ROUTING_KEY_AF5);
                return stripRoutingKey(vd);
            }
        };
    }

    /**
     * Returns the name of the first field / record component that carries an AF4 or AF5
     * {@code @RoutingKey} annotation, walking both the primary-constructor record components and
     * the class body. Returns {@code null} if no such field exists.
     */
    private static String findRoutingKeyFieldName(J.ClassDeclaration cd) {
        if (cd.getPrimaryConstructor() != null) {
            for (Statement stmt : cd.getPrimaryConstructor()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (hasRoutingKeyAnnotation(vd) && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
        }
        if (cd.getBody() != null) {
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (hasRoutingKeyAnnotation(vd) && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Removes the {@code @RoutingKey} annotation from all record components and body fields of
     * the given class declaration, returning the rewritten class declaration.
     */
    private static J.ClassDeclaration removeRoutingKeyFromComponentsAndFields(J.ClassDeclaration cd) {
        J.ClassDeclaration result = cd;
        if (result.getPrimaryConstructor() != null) {
            List<Statement> rewritten = new ArrayList<>(result.getPrimaryConstructor().size());
            for (Statement stmt : result.getPrimaryConstructor()) {
                if (stmt instanceof J.VariableDeclarations) {
                    rewritten.add(stripRoutingKey((J.VariableDeclarations) stmt));
                } else {
                    rewritten.add(stmt);
                }
            }
            result = result.withPrimaryConstructor(rewritten);
        }
        if (result.getBody() != null) {
            List<Statement> rewritten = new ArrayList<>(result.getBody().getStatements().size());
            for (Statement stmt : result.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    rewritten.add(stripRoutingKey((J.VariableDeclarations) stmt));
                } else {
                    rewritten.add(stmt);
                }
            }
            result = result.withBody(result.getBody().withStatements(rewritten));
        }
        return result;
    }

    /**
     * Removes any {@code @RoutingKey} leading annotation from {@code vd}, fixing up the spacing
     * that would otherwise be left attached to the now-removed annotation.
     */
    private static J.VariableDeclarations stripRoutingKey(J.VariableDeclarations vd) {
        List<J.Annotation> remaining = new ArrayList<>();
        boolean removed = false;
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (isRoutingKey(ann)) {
                removed = true;
            } else {
                remaining.add(ann);
            }
        }
        if (!removed) {
            return vd;
        }
        J.VariableDeclarations result = vd.withLeadingAnnotations(remaining);
        // When the removed annotation was the only one, the whitespace between it and the next
        // sibling (modifier or type) stays attached to that sibling; clear it so we don't leave
        // a stray space behind.
        if (remaining.isEmpty()) {
            if (!result.getModifiers().isEmpty()) {
                result = result.withModifiers(Space.formatFirstPrefix(
                        result.getModifiers(),
                        Space.firstPrefix(result.getModifiers()).withWhitespace("")));
            } else if (result.getTypeExpression() != null) {
                result = result.withTypeExpression(
                        result.getTypeExpression().withPrefix(
                                result.getTypeExpression().getPrefix().withWhitespace("")));
            }
        }
        return result;
    }

    private static boolean hasRoutingKeyAnnotation(J.VariableDeclarations vd) {
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (isRoutingKey(ann)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRoutingKey(J.Annotation ann) {
        if (TypeUtils.isOfClassType(ann.getType(), ROUTING_KEY_AF4)
                || TypeUtils.isOfClassType(ann.getType(), ROUTING_KEY_AF5)) {
            return true;
        }
        if (ann.getAnnotationType() instanceof J.Identifier) {
            return "RoutingKey".equals(
                    ((J.Identifier) ann.getAnnotationType()).getSimpleName());
        }
        return false;
    }

    private static boolean hasAnnotation(J.ClassDeclaration cd, String fqn) {
        for (J.Annotation ann : cd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), fqn)) {
                return true;
            }
            if (ann.getAnnotationType() instanceof J.Identifier) {
                String simpleName = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
                if (fqn.endsWith("." + simpleName)) {
                    return true;
                }
            }
        }
        return false;
    }

}
