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
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Scans for methods annotated with {@code @CommandHandler} and annotates their command parameter
 * types with {@code @Command}.
 * <p>
 * <strong>Routing-key lifting</strong>: If the command class has a field — or a Java
 * {@code record} component, or a Kotlin {@code data class} primary constructor parameter —
 * annotated with {@code @RoutingKey}, that annotation is removed and replaced with
 * {@code @Command(routingKey = "fieldName")} on the class. Likewise, if the field carries
 * {@code @TargetAggregateIdentifier} (AF4) or its post-rename AF5 successor
 * {@code @TargetEntityId}, the field name is lifted onto {@code @Command#routingKey}; unlike
 * {@code @RoutingKey}, those annotations are <em>preserved</em> on the field.
 * <p>
 * <strong>Precedence</strong>: When a class declares both an explicit {@code @RoutingKey} field
 * and a {@code @TargetAggregateIdentifier} / {@code @TargetEntityId} field, the explicit
 * {@code @RoutingKey} field wins regardless of declaration order, matching AF4 routing semantics
 * where {@code @RoutingKey} takes precedence over the target-identifier annotations.
 * <p>
 * <strong>Idempotent updates</strong>: If the class already carries a {@code @Command}
 * annotation whose {@code routingKey} attribute is missing or blank, the recipe adds the
 * attribute rather than skipping the class. A {@code @Command} with a non-empty
 * {@code routingKey} is left untouched.
 * <p>
 * <strong>Ordering</strong>: Java records and class fields complete the lift in a single
 * OpenRewrite cycle: the recipe walks {@code J.ClassDeclaration.primaryConstructor} (records)
 * and {@code J.ClassDeclaration.body} (fields) directly, and crucially performs the
 * {@code @RoutingKey} removal AFTER {@code JavaTemplate.apply} adds the class-level
 * {@code @Command} — {@code JavaTemplate.apply} walks the visitor's cursor (still pointing at
 * the un-modified class declaration) and would otherwise discard any child mutations applied
 * beforehand. Kotlin {@code data class} primary-constructor parameters live outside
 * {@code J.ClassDeclaration} (the Kotlin parser keeps them on a sibling Kotlin LST node), so
 * for Kotlin the recipe falls back to the {@code visitVariableDeclarations} hook to capture
 * the parameter name and to strip the now-orphaned {@code @RoutingKey} annotation in a second
 * cycle.
 * <p>
 * Both AF4 ({@code org.axonframework.commandhandling.CommandHandler}) and AF5
 * ({@code org.axonframework.messaging.commandhandling.annotation.CommandHandler}) FQNs are matched
 * so the recipe is safe to run before or after {@code Axon4ToAxon5Messaging}.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class AddCommandAnnotation extends ScanningRecipe<AddCommandAnnotation.Accumulator> {

    private static final String COMMAND_HANDLER_AF4 = "org.axonframework.commandhandling.CommandHandler";
    private static final String COMMAND_HANDLER_AF5 = "org.axonframework.messaging.commandhandling.annotation.CommandHandler";
    private static final String COMMAND_FQN = "org.axonframework.messaging.commandhandling.annotation.Command";
    private static final String ROUTING_KEY_AF4 = "org.axonframework.commandhandling.RoutingKey";
    private static final String ROUTING_KEY_AF5 = "org.axonframework.messaging.commandhandling.RoutingKey";
    // @TargetAggregateIdentifier (AF4) marks the field that resolves the entity to route the
    // command to. AF5 splits this into two annotations: @TargetEntityId on the field stays
    // (renamed via ChangeType), and a class-level @Command(routingKey = "<fieldName>") declares
    // which field carries the routing key for the command bus. If a class lacks an explicit
    // @RoutingKey but has a @TargetAggregateIdentifier / @TargetEntityId field, we lift that
    // field's name onto @Command#routingKey so routing keeps working
    private static final String TARGET_AGGREGATE_ID_AF4 =
            "org.axonframework.modelling.command.TargetAggregateIdentifier";
    // Post-ChangePackage (modelling.command -> modelling.entity) shape.
    private static final String TARGET_AGGREGATE_ID_AF5_INTERMEDIATE =
            "org.axonframework.modelling.entity.TargetAggregateIdentifier";
    // Post-rename (TargetAggregateIdentifier -> TargetEntityId) shape — the AF5 target.
    private static final String TARGET_ENTITY_ID_AF5 =
            "org.axonframework.modelling.annotation.TargetEntityId";
    private static final String ROUTING_KEY_FIELD_MESSAGE = "axon4to5.routingKeyField";
    // Marks that the captured routing-key field carries an explicit @RoutingKey (Kotlin fallback),
    // so a later @TargetEntityId / @TargetAggregateIdentifier parameter does not override it.
    private static final String ROUTING_KEY_FIELD_EXPLICIT_MESSAGE = "axon4to5.routingKeyFieldExplicit";

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
                        || !acc.commandTypeFqns.contains(classDecl.getType().getFullyQualifiedName())) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                boolean hasCommand = hasAnnotation(classDecl, COMMAND_FQN);
                // If @Command is already present with a non-empty routingKey the migration is
                // complete — leave the class unchanged.
                if (hasCommand && hasRoutingKeyAttribute(classDecl)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                // Find the routing-key field/component name BEFORE adding @Command. Direct walk
                // covers Java record components (in `getPrimaryConstructor()`) and class body
                // fields. For Kotlin data classes, the primary-constructor parameters live
                // outside `J.ClassDeclaration` on a sibling Kotlin LST node, so we fall back to
                // a super-visit that runs the `visitVariableDeclarations` hook below — that
                // hook writes the captured name onto this cursor's message bus.
                String routingKeyField = findRoutingKeyFieldName(classDecl);
                boolean usedKotlinFallback = (routingKeyField == null);
                if (usedKotlinFallback) {
                    super.visitClassDeclaration(classDecl, ctx);
                    routingKeyField = getCursor().getMessage(ROUTING_KEY_FIELD_MESSAGE);
                }

                if (hasCommand) {
                    // @Command exists but lacks routingKey — add routingKey if we found a source.
                    if (routingKeyField == null) {
                        // No routing-key source found; leave class unchanged.
                        // If usedKotlinFallback we already ran super; otherwise run it now.
                        return usedKotlinFallback
                                ? classDecl
                                : super.visitClassDeclaration(classDecl, ctx);
                    }
                    // Pass the outer cursor so JavaTemplate can walk up to the CompilationUnit
                    // for import management and type resolution.
                    J.ClassDeclaration updated = addRoutingKeyToCommandAnnotation(
                            classDecl, routingKeyField, getCursor());
                    maybeAddImport(COMMAND_FQN, null, false);
                    return super.visitClassDeclaration(updated, ctx);
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
                // walk through `J.ClassDeclaration` returned nothing. An explicit @RoutingKey
                // parameter wins over a @TargetEntityId / @TargetAggregateIdentifier parameter
                // regardless of order, matching AF4 routing semantics: an explicit match always
                // (over)writes the captured name, while a target-identifier match is skipped once
                // an explicit one has been captured.
                if (!vd.getVariables().isEmpty()) {
                    String name = vd.getVariables().get(0).getSimpleName();
                    boolean explicit = hasExplicitRoutingKeyAnnotation(vd);
                    Cursor enclosingClassCursor = getCursor()
                            .dropParentUntil(it -> it instanceof J.ClassDeclaration);
                    boolean alreadyExplicit = Boolean.TRUE.equals(
                            enclosingClassCursor.getMessage(ROUTING_KEY_FIELD_EXPLICIT_MESSAGE));
                    if (explicit || !alreadyExplicit) {
                        enclosingClassCursor.putMessage(ROUTING_KEY_FIELD_MESSAGE, name);
                        if (explicit) {
                            enclosingClassCursor.putMessage(ROUTING_KEY_FIELD_EXPLICIT_MESSAGE, true);
                        }
                    }
                }
                // Only remove the @RoutingKey import when the variable actually carries a
                // @RoutingKey annotation. @TargetAggregateIdentifier / @TargetEntityId are
                // preserved on the field, so their imports must not be touched here.
                if (hasExplicitRoutingKeyAnnotation(vd)) {
                    maybeRemoveImport(ROUTING_KEY_AF4);
                    maybeRemoveImport(ROUTING_KEY_AF5);
                }
                return stripRoutingKey(vd);
            }
        };
    }

    /**
     * Returns the name of the field / record component whose name should become the
     * {@code @Command#routingKey}, walking both the primary-constructor record components and the
     * class body. Returns {@code null} if no such field exists (e.g., for Kotlin
     * {@code data class} primary-constructor parameters, which live outside
     * {@code J.ClassDeclaration} and require the Kotlin-fallback path).
     * <p>
     * An explicit {@code @RoutingKey} field takes precedence over a
     * {@code @TargetAggregateIdentifier} / {@code @TargetEntityId} field regardless of declaration
     * order, matching AF4 routing semantics. Only when no explicit {@code @RoutingKey} field exists
     * does the first target-identifier field supply the name.
     */
    private static String findRoutingKeyFieldName(J.ClassDeclaration cd) {
        String explicit = findFieldName(cd, AddCommandAnnotation::hasExplicitRoutingKeyAnnotation);
        if (explicit != null) {
            return explicit;
        }
        return findFieldName(cd, AddCommandAnnotation::hasTargetIdentifierAnnotation);
    }

    /**
     * Returns the name of the first field / record component matching {@code matcher}, walking the
     * primary-constructor record components first and then the class body, or {@code null} when no
     * field matches.
     */
    private static String findFieldName(J.ClassDeclaration cd, Predicate<J.VariableDeclarations> matcher) {
        if (cd.getPrimaryConstructor() != null) {
            for (Statement stmt : cd.getPrimaryConstructor()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (matcher.test(vd) && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
        }
        if (cd.getBody() != null) {
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (matcher.test(vd) && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the class-level {@code @Command} annotation on {@code cd} already
     * declares a non-blank {@code routingKey} attribute. Used to decide whether the migration is
     * already complete and the class should be left unchanged.
     */
    private static boolean hasRoutingKeyAttribute(J.ClassDeclaration cd) {
        for (J.Annotation ann : cd.getLeadingAnnotations()) {
            if (!TypeUtils.isOfClassType(ann.getType(), COMMAND_FQN)) {
                if (!(ann.getAnnotationType() instanceof J.Identifier)) {
                    continue;
                }
                String simpleName = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
                if (!COMMAND_FQN.endsWith("." + simpleName)) {
                    continue;
                }
            }
            // Found @Command — inspect its arguments for routingKey = "...".
            List<Expression> args = ann.getArguments();
            if (args == null || args.isEmpty()) {
                return false;
            }
            for (Expression arg : args) {
                if (!(arg instanceof J.Assignment)) {
                    continue;
                }
                J.Assignment assignment = (J.Assignment) arg;
                if (!(assignment.getVariable() instanceof J.Identifier)) {
                    continue;
                }
                if (!"routingKey".equals(((J.Identifier) assignment.getVariable()).getSimpleName())) {
                    continue;
                }
                Expression val = assignment.getAssignment();
                if (val instanceof J.Literal) {
                    Object literalVal = ((J.Literal) val).getValue();
                    return literalVal instanceof String && !((String) literalVal).isEmpty();
                }
                return true; // non-literal value — assume non-empty
            }
            return false; // @Command found but no routingKey attribute
        }
        return false;
    }

    /**
     * Replaces the class-level {@code @Command} annotation on {@code classDecl} with
     * {@code @Command(routingKey = "fieldName")} using {@code JavaTemplate.apply}.
     * <p>
     * The {@code outerCursor} must be the visitor cursor positioned at {@code classDecl} so that
     * {@code JavaTemplate} can walk up to the {@code CompilationUnit} for import management and
     * type resolution. The caller is responsible for calling {@code maybeAddImport} after this
     * method returns, because this helper operates on the already-present annotation and the
     * import for {@code @Command} should already exist.
     */
    private static J.ClassDeclaration addRoutingKeyToCommandAnnotation(J.ClassDeclaration classDecl,
                                                                        String routingKeyField,
                                                                        Cursor outerCursor) {
        List<J.Annotation> updatedAnns = new ArrayList<>(classDecl.getLeadingAnnotations());
        for (int i = 0; i < updatedAnns.size(); i++) {
            J.Annotation ann = updatedAnns.get(i);
            if (!TypeUtils.isOfClassType(ann.getType(), COMMAND_FQN)) {
                if (!(ann.getAnnotationType() instanceof J.Identifier)) {
                    continue;
                }
                String simpleName = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
                if (!COMMAND_FQN.endsWith("." + simpleName)) {
                    continue;
                }
            }
            // Build a cursor for the annotation using the outer visitor's cursor as parent so
            // that JavaTemplate can walk up to the CompilationUnit for classpath / imports.
            Cursor annCursor = new Cursor(outerCursor, ann);
            J.Annotation replacement = JavaTemplate.builder("@Command(routingKey = \"" + routingKeyField + "\")")
                    .imports(COMMAND_FQN)
                    .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                    .build()
                    .apply(annCursor, ann.getCoordinates().replace());
            updatedAnns.set(i, replacement);
            break;
        }
        return classDecl.withLeadingAnnotations(updatedAnns);
    }

    /**
     * Returns {@code true} when {@code vd} has at least one leading {@code @RoutingKey}
     * annotation (AF4 or AF5). Unlike {@link #hasRoutingKeyAnnotation(J.VariableDeclarations)},
     * this method does <em>not</em> match {@code @TargetAggregateIdentifier} or
     * {@code @TargetEntityId}, which are preserved on the field rather than removed.
     */
    private static boolean hasExplicitRoutingKeyAnnotation(J.VariableDeclarations vd) {
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (isRoutingKey(ann)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code vd} carries a {@code @TargetAggregateIdentifier} or
     * {@code @TargetEntityId} annotation (AF4 or AF5). Used to locate the routing-key field only
     * when no explicit {@code @RoutingKey} field is present.
     */
    private static boolean hasTargetIdentifierAnnotation(J.VariableDeclarations vd) {
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (isTargetIdentifier(ann)) {
                return true;
            }
        }
        return false;
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
            if (isRoutingKey(ann) || isTargetIdentifier(ann)) {
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

    /**
     * Matches the AF4 {@code @TargetAggregateIdentifier} and its post-rename AF5
     * {@code @TargetEntityId} successor (including the transient post-{@code ChangePackage}
     * shape). When this annotation is on a command field and no explicit {@code @RoutingKey}
     * exists, the field name becomes the routing key on the class-level {@code @Command}.
     */
    private static boolean isTargetIdentifier(J.Annotation ann) {
        if (TypeUtils.isOfClassType(ann.getType(), TARGET_AGGREGATE_ID_AF4)
                || TypeUtils.isOfClassType(ann.getType(), TARGET_AGGREGATE_ID_AF5_INTERMEDIATE)
                || TypeUtils.isOfClassType(ann.getType(), TARGET_ENTITY_ID_AF5)) {
            return true;
        }
        if (ann.getAnnotationType() instanceof J.Identifier) {
            String name = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
            return "TargetAggregateIdentifier".equals(name) || "TargetEntityId".equals(name);
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
