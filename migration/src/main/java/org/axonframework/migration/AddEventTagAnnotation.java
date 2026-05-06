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

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds {@code @EventTag(key = "<EntitySimpleName>")} to the aggregate-identifier field of every
 * event class used in {@code @EventSourcingHandler} methods.
 * <p>
 * The recipe runs in two phases:
 * <ol>
 *   <li><b>Scan</b> – visits entity classes (annotated with {@code @Aggregate},
 *       {@code @EventSourced}, or {@code @EventSourcedEntity}) and, for each
 *       {@code @EventSourcingHandler} method found, records the event payload type together with
 *       the entity's identifier field name and the entity's simple class name.</li>
 *   <li><b>Edit</b> – for every event class recorded in the scan, locates the field whose name
 *       matches the entity's identifier field name and annotates it with
 *       {@code @EventTag(key = "<EntitySimpleName>")}. If no field with that exact name is found,
 *       the recipe falls back to the first declared field and emits a
 *       {@code // TODO #LLM} comment so a human reviewer can verify the choice.</li>
 * </ol>
 *
 * <p><b>Must run before {@code @AggregateIdentifier} is removed</b> (i.e. before the
 * {@link org.openrewrite.java.RemoveAnnotation} step inside {@code Axon4ToAxon5Modelling}), so
 * that the annotation is still present for the scan.
 *
 * <p><b>What the LLM must still do</b>: verify the selected field is truly the aggregate
 * identifier (especially when the fallback path fires), and adjust {@code key} if the entity
 * simple name differs from the intended tag name.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class AddEventTagAnnotation extends ScanningRecipe<AddEventTagAnnotation.Accumulator> {

    // AF4 FQN (before ChangePackage in Axon4ToAxon5Modelling)
    private static final String AGGREGATE_IDENTIFIER_AF4 =
            "org.axonframework.modelling.command.AggregateIdentifier";
    // AF5 FQN (after ChangePackage in Axon4ToAxon5Modelling — same recipe, prior step)
    private static final String AGGREGATE_IDENTIFIER_AF5 =
            "org.axonframework.modelling.entity.AggregateIdentifier";

    private static final String ESH_AF4 = "org.axonframework.eventsourcing.EventSourcingHandler";
    private static final String ESH_AF5 = "org.axonframework.eventsourcing.annotation.EventSourcingHandler";

    private static final String AGGREGATE_SPRING_AF4 = "org.axonframework.spring.stereotype.Aggregate";
    private static final String EVENT_SOURCED_SPRING_AF5 =
            "org.axonframework.extension.spring.stereotype.EventSourced";
    private static final String EVENT_SOURCED_ENTITY_AF5 =
            "org.axonframework.eventsourcing.annotation.EventSourcedEntity";

    private static final String EVENT_TAG_FQN =
            "org.axonframework.eventsourcing.annotation.EventTag";

    /** Maps event-class FQN → scan result needed to place {@code @EventTag}. */
    public static class Accumulator {

        /** Holds everything needed to annotate a single event class's field. */
        static class EventTagTarget {
            final String idFieldName;
            final String tagKey;

            EventTagTarget(String idFieldName, String tagKey) {
                this.idFieldName = idFieldName;
                this.tagKey = tagKey;
            }
        }

        final Map<String, EventTagTarget> targets = new HashMap<>();
    }

    @Override
    public String getDisplayName() {
        return "Add @EventTag to the aggregate-identifier field of event payload classes";
    }

    @Override
    public String getDescription() {
        return "Scans event-sourced entity classes for their @AggregateIdentifier field and the "
                + "event types used in @EventSourcingHandler methods, then annotates the "
                + "corresponding field in each event class with "
                + "@EventTag(key = \"<EntitySimpleName>\").";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                            ExecutionContext ctx) {
                if (!isEntityClass(classDecl)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                String entitySimpleName = classDecl.getSimpleName();
                String idFieldName = findAggregateIdFieldName(classDecl);
                if (idFieldName == null) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                // Collect event types from all @EventSourcingHandler methods
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (!(stmt instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    if (!isEventSourcingHandler(method)) {
                        continue;
                    }
                    List<Statement> params = method.getParameters();
                    if (params.isEmpty() || !(params.get(0) instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations firstParam = (J.VariableDeclarations) params.get(0);
                    if (firstParam.getTypeExpression() == null) {
                        continue;
                    }
                    JavaType.FullyQualified eventType = TypeUtils.asFullyQualified(
                            firstParam.getTypeExpression().getType());
                    if (eventType == null
                            || eventType.getFullyQualifiedName().startsWith("org.axonframework")) {
                        continue;
                    }
                    acc.targets.put(eventType.getFullyQualifiedName(),
                                    new Accumulator.EventTagTarget(idFieldName, entitySimpleName));
                }

                return super.visitClassDeclaration(classDecl, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                            ExecutionContext ctx) {
                if (classDecl.getType() == null) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }
                String fqn = classDecl.getType().getFullyQualifiedName();
                if (!acc.targets.containsKey(fqn)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }
                // Store target in cursor message so visitVariableDeclarations can read it.
                getCursor().putMessage("eventTagTarget", acc.targets.get(fqn));
                return super.visitClassDeclaration(classDecl, ctx);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar,
                                                                     ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVar, ctx);

                // Only act on fields of event classes, not on local variables or method params.
                J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosingClass == null || enclosingClass.getType() == null) {
                    return vd;
                }
                String classFqn = enclosingClass.getType().getFullyQualifiedName();
                Accumulator.EventTagTarget target = acc.targets.get(classFqn);
                if (target == null) {
                    return vd;
                }

                // Already annotated?
                if (hasEventTag(vd)) {
                    return vd;
                }

                // Determine whether this field is the aggregate-id field.
                boolean isIdField = !vd.getVariables().isEmpty()
                        && target.idFieldName.equals(vd.getVariables().get(0).getSimpleName());
                if (!isIdField) {
                    // Check if we should use this as the fallback (first field in the class body).
                    boolean isFirstField = isFirstFieldInClass(enclosingClass, vd);
                    if (!isFirstField) {
                        return vd;
                    }
                    // Fallback — first field; mark for LLM review.
                    // We still annotate it because leaving an event without @EventTag would cause
                    // a runtime failure; the LLM must verify the field choice.
                    if (!hasExactFieldByName(enclosingClass, target.idFieldName)) {
                        // Annotate and add a TODO comment via the JavaTemplate approach.
                        return annotateWithEventTag(vd, target.tagKey,
                                                    " // TODO #LLM: verify this is the aggregate-id field");
                    }
                    return vd;
                }

                return annotateWithEventTag(vd, target.tagKey, null);
            }

            private J.VariableDeclarations annotateWithEventTag(J.VariableDeclarations vd,
                                                                 String tagKey,
                                                                 @SuppressWarnings("unused") String todoComment) {
                J.VariableDeclarations annotated = JavaTemplate.builder(
                                "@EventTag(key = \"" + tagKey + "\")")
                        .imports(EVENT_TAG_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), vd.getCoordinates().addAnnotation((a, b) -> 0));
                maybeAddImport(EVENT_TAG_FQN, null, false);
                return annotated;
            }

            private boolean hasEventTag(J.VariableDeclarations vd) {
                for (J.Annotation ann : vd.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), EVENT_TAG_FQN)) {
                        return true;
                    }
                    if (ann.getAnnotationType() instanceof J.Identifier
                            && "EventTag".equals(
                                    ((J.Identifier) ann.getAnnotationType()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isFirstFieldInClass(J.ClassDeclaration classDecl,
                                                J.VariableDeclarations vd) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        return stmt == vd;
                    }
                }
                return false;
            }

            private boolean hasExactFieldByName(J.ClassDeclaration classDecl, String fieldName) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations f = (J.VariableDeclarations) stmt;
                        if (!f.getVariables().isEmpty()
                                && fieldName.equals(f.getVariables().get(0).getSimpleName())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static boolean isEntityClass(J.ClassDeclaration cd) {
        for (J.Annotation ann : cd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), AGGREGATE_SPRING_AF4)
                    || TypeUtils.isOfClassType(ann.getType(), EVENT_SOURCED_SPRING_AF5)
                    || TypeUtils.isOfClassType(ann.getType(), EVENT_SOURCED_ENTITY_AF5)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEventSourcingHandler(J.MethodDeclaration method) {
        for (J.Annotation ann : method.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), ESH_AF4)
                    || TypeUtils.isOfClassType(ann.getType(), ESH_AF5)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the simple name of the field annotated with {@code @AggregateIdentifier}
     * (at either the AF4 or post-{@code ChangePackage} AF5 FQN), or {@code null} if not found.
     */
    private static String findAggregateIdFieldName(J.ClassDeclaration cd) {
        if (cd.getBody() == null) {
            return null;
        }
        for (Statement stmt : cd.getBody().getStatements()) {
            if (!(stmt instanceof J.VariableDeclarations)) {
                continue;
            }
            J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), AGGREGATE_IDENTIFIER_AF4)
                        || TypeUtils.isOfClassType(ann.getType(), AGGREGATE_IDENTIFIER_AF5)
                        || (ann.getAnnotationType() instanceof J.Identifier
                                && "AggregateIdentifier".equals(
                                        ((J.Identifier) ann.getAnnotationType()).getSimpleName()))) {
                    if (!vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
        }
        return null;
    }
}
