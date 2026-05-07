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
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.marker.PrimaryConstructor;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;
import org.jspecify.annotations.Nullable;

import java.util.Collections;

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

    /**
     * {@link ExecutionContext} key under which this recipe publishes a
     * {@code Map<entityClassFqn, idTypeFqn>} for downstream recipes (notably
     * {@link ConfigureEventSourcedAnnotation}) to consume after this recipe's
     * scan has run while {@code @AggregateIdentifier} is still on the source.
     */
    static final String SHARED_ID_TYPES_KEY =
            "axon.migration.aggregateIdentifierFieldTypes";

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

                // Publish the @AggregateIdentifier field's declared type so
                // ConfigureEventSourcedAnnotation can populate @EventSourced(idType=...)
                // even when it runs after RemoveAnnotation has stripped @AggregateIdentifier.
                if (classDecl.getType() != null) {
                    String idTypeFqn = findAggregateIdFieldTypeFqn(classDecl);
                    if (idTypeFqn != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> shared =
                                (Map<String, String>) ctx.getMessage(SHARED_ID_TYPES_KEY);
                        if (shared == null) {
                            shared = new HashMap<>();
                            ctx.putMessage(SHARED_ID_TYPES_KEY, shared);
                        }
                        shared.put(classDecl.getType().getFullyQualifiedName(), idTypeFqn);
                    }
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

                // Only act on fields and record components of event classes — never on regular
                // method parameters or local variables. The check has two carve-outs:
                // - Java records: a method parameter inside a static factory (e.g.
                //   `event(Id id)`) shares the enclosing class with the record header, so a
                //   plain "skip everything inside a method" rule would still leave the record
                //   components handled correctly because they live directly under the class.
                // - Kotlin data classes: primary-constructor parameters surface as
                //   J.VariableDeclarations whose enclosing J.MethodDeclaration carries a
                //   {@link PrimaryConstructor} marker. Those ARE the class's properties — they
                //   need the @EventTag treatment, so we explicitly let them through.
                J.MethodDeclaration enclosingMethod =
                        getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (enclosingMethod != null && !isKotlinPrimaryConstructor(enclosingMethod)) {
                    return vd;
                }
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
                if (isKotlinSource()) {
                    // Kotlin path — JavaTemplate.addAnnotation produces inconsistent layout on
                    // data class primary-constructor params (annotation lost or pushed to a
                    // weird indent). Build the J.Annotation directly from LST primitives and
                    // prepend it to the leading-annotation list, with an explicit newline+indent
                    // prefix between the annotation and the val/var keyword so it lands on its
                    // own line above the field.
                    J.Annotation tag = buildEventTagAnnotation(tagKey);
                    J.VariableDeclarations annotated = prependAnnotationOnNewLine(vd, tag);
                    maybeAddImport(EVENT_TAG_FQN, null, false);
                    return annotated;
                }
                J.VariableDeclarations annotated = JavaTemplate.builder(
                                "@EventTag(key = \"" + tagKey + "\")")
                        .imports(EVENT_TAG_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), vd.getCoordinates().addAnnotation((a, b) -> 0));
                maybeAddImport(EVENT_TAG_FQN, null, false);
                return forceAnnotationOnOwnLine(annotated);
            }

            private boolean isKotlinSource() {
                return getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit;
            }

            /**
             * Construct an {@code @EventTag(key = "...")} annotation as a synthetic
             * {@link J.Annotation}. Building the annotation through LST primitives skips
             * {@link JavaTemplate}'s parsing pipeline entirely — that pipeline cannot render
             * Kotlin's {@code val}/{@code var}-shaped {@link J.VariableDeclarations} nodes
             * back through a Java placeholder, which is why the template-driven path produces
             * lopsided output on data class primary-constructor params.
             */
            private J.Annotation buildEventTagAnnotation(String tagKey) {
                J.Identifier name = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "EventTag",
                        JavaType.ShallowClass.build(EVENT_TAG_FQN),
                        null);
                J.Identifier keyIdent = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "key",
                        null,
                        null);
                // The value's leading space renders between `=` and the literal — pairing it
                // with the JLeftPadded's `before` (which renders BEFORE `=`) yields the
                // canonical `key = "value"` shape.
                J.Literal keyValue = new J.Literal(
                        Tree.randomId(),
                        Space.format(" "),
                        Markers.EMPTY,
                        tagKey,
                        "\"" + tagKey + "\"",
                        null,
                        JavaType.Primitive.String);
                J.Assignment assignment = new J.Assignment(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        keyIdent,
                        new org.openrewrite.java.tree.JLeftPadded<>(
                                Space.format(" "),
                                keyValue,
                                Markers.EMPTY),
                        null);
                org.openrewrite.java.tree.JContainer<org.openrewrite.java.tree.Expression> args =
                        org.openrewrite.java.tree.JContainer.build(
                                Space.EMPTY,
                                Collections.singletonList(
                                        new org.openrewrite.java.tree.JRightPadded<org.openrewrite.java.tree.Expression>(
                                                assignment, Space.EMPTY, Markers.EMPTY)),
                                Markers.EMPTY);
                return new J.Annotation(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        name,
                        args);
            }

            /**
             * Prepends {@code annotation} to {@code vd}'s leading annotations and pushes the
             * declaration's modifiers (or the type expression / first variable) onto the line
             * below. The new annotation inherits the slot's existing leading whitespace
             * (preserving the outer indent), and a fresh {@code "\n" + indent} prefix is
             * spliced between the annotation and whatever previously held that whitespace.
             */
            private J.VariableDeclarations prependAnnotationOnNewLine(J.VariableDeclarations vd,
                                                                       J.Annotation annotation) {
                String indent = trailingIndent(vd.getPrefix().getWhitespace());
                Space newlineIndent = Space.format("\n" + indent);
                J.VariableDeclarations withAnnotation = vd.withLeadingAnnotations(
                        ListUtils.concat(annotation, vd.getLeadingAnnotations()));
                if (vd.getLeadingAnnotations().isEmpty()) {
                    // First annotation — re-flow the declaration's downstream nodes so the
                    // annotation block sits on its own line above the val/var keyword.
                    if (!withAnnotation.getModifiers().isEmpty()) {
                        return withAnnotation.withModifiers(
                                ListUtils.mapFirst(withAnnotation.getModifiers(),
                                        m -> m.withPrefix(newlineIndent)));
                    }
                    if (withAnnotation.getTypeExpression() != null) {
                        return withAnnotation.withTypeExpression(
                                withAnnotation.getTypeExpression().withPrefix(newlineIndent));
                    }
                    return withAnnotation;
                }
                // Existing annotations: bump the previous first annotation onto a new line.
                J.Annotation oldFirst = vd.getLeadingAnnotations().get(0);
                Space oldFirstPrefix = oldFirst.getPrefix();
                return vd.withLeadingAnnotations(ListUtils.concat(
                        annotation.withPrefix(oldFirstPrefix),
                        ListUtils.mapFirst(vd.getLeadingAnnotations(),
                                first -> first.withPrefix(newlineIndent))));
            }

            private String trailingIndent(String whitespace) {
                int idx = whitespace.lastIndexOf('\n');
                return idx < 0 ? whitespace : whitespace.substring(idx + 1);
            }

            /**
             * For record headers the components live on a single (or comma-separated) line, so
             * {@link JavaTemplate#apply} inlines the freshly added annotation as
             * {@code @EventTag(...) String id}. We push the type/modifier onto its own line by
             * reusing a newline+indent prefix sourced from this declaration, its annotation, or
             * a sibling record component, so the result reads
             * <pre>{@code
             * @EventTag(key = "Foo")
             * String id
             * }</pre>
             * For regular class fields the annotation is already on its own line and this method
             * is a no-op.
             */
            private J.VariableDeclarations forceAnnotationOnOwnLine(J.VariableDeclarations vd) {
                if (vd.getLeadingAnnotations().isEmpty()) {
                    return vd;
                }
                Space indent = resolveIndent(vd);
                if (indent == null) {
                    return vd;
                }
                // Promote the VariableDeclarations' own prefix to a full indent when it only
                // carries a bare newline; this happens for the first record component, whose
                // leading whitespace is held by the enclosing JContainer rather than the VD.
                if (!hasIndent(vd.getPrefix()) && vd.getPrefix().getWhitespace().contains("\n")) {
                    vd = vd.withPrefix(indent);
                }
                if (!vd.getModifiers().isEmpty()) {
                    J.Modifier first = vd.getModifiers().get(0);
                    if (first.getPrefix().getWhitespace().contains("\n")) {
                        return vd;
                    }
                    Space finalIndent = indent;
                    return vd.withModifiers(ListUtils.mapFirst(vd.getModifiers(),
                                                                m -> m.withPrefix(finalIndent)));
                }
                if (vd.getTypeExpression() != null
                        && !vd.getTypeExpression().getPrefix().getWhitespace().contains("\n")) {
                    return vd.withTypeExpression(vd.getTypeExpression().withPrefix(indent));
                }
                return vd;
            }

            /**
             * Resolve a newline + indent {@link Space} for {@code vd}, checking — in order — the
             * trailing annotation, the variable declaration itself, and any sibling record
             * component / class field. The first record component's indent often lives on the
             * enclosing {@code JContainer} (so its own prefix is just {@code "\n"} with no
             * spaces), making siblings the only reliable source of a usable indent for it.
             */
            private @Nullable Space resolveIndent(J.VariableDeclarations vd) {
                Space candidate = vd.getLeadingAnnotations()
                                     .get(vd.getLeadingAnnotations().size() - 1)
                                     .getPrefix();
                if (hasIndent(candidate)) {
                    return candidate;
                }
                if (hasIndent(vd.getPrefix())) {
                    return vd.getPrefix();
                }
                J.ClassDeclaration clazz = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (clazz == null) {
                    return null;
                }
                if (clazz.getPadding().getPrimaryConstructor() != null) {
                    for (Statement s : clazz.getPadding().getPrimaryConstructor().getElements()) {
                        if (s == vd || !(s instanceof J.VariableDeclarations)) {
                            continue;
                        }
                        Space siblingPrefix = ((J.VariableDeclarations) s).getPrefix();
                        if (hasIndent(siblingPrefix)) {
                            return siblingPrefix;
                        }
                    }
                }
                if (clazz.getBody() != null) {
                    for (Statement s : clazz.getBody().getStatements()) {
                        if (s == vd || !(s instanceof J.VariableDeclarations)) {
                            continue;
                        }
                        Space siblingPrefix = ((J.VariableDeclarations) s).getPrefix();
                        if (hasIndent(siblingPrefix)) {
                            return siblingPrefix;
                        }
                    }
                }
                return null;
            }

            /** A {@link Space} is a usable indent only if it has indent chars after a newline. */
            private static boolean hasIndent(Space space) {
                String ws = space.getWhitespace();
                int nl = ws.lastIndexOf('\n');
                return nl >= 0 && nl < ws.length() - 1;
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

            /**
             * Returns the {@link J.VariableDeclarations} fields that belong to {@code classDecl}
             * in source-declaration order, walking three places:
             * <ol>
             *   <li>{@link J.ClassDeclaration#getPrimaryConstructor()} — Java records and
             *   anything else that exposes its primary-constructor params explicitly;</li>
             *   <li>any {@link J.MethodDeclaration} in the body marked with the Kotlin
             *   {@link PrimaryConstructor} marker — Kotlin data classes embed primary-constructor
             *   params here rather than in {@code getPrimaryConstructor()};</li>
             *   <li>the class body itself — regular Java fields and Kotlin {@code val}/{@code var}
             *   class members (the latter wrapped in {@link K.Property}).</li>
             * </ol>
             */
            private List<J.VariableDeclarations> classFields(J.ClassDeclaration classDecl) {
                List<J.VariableDeclarations> fields = new java.util.ArrayList<>();
                if (classDecl.getPrimaryConstructor() != null) {
                    for (Statement stmt : classDecl.getPrimaryConstructor()) {
                        J.VariableDeclarations field = unwrapVariableDeclarations(stmt);
                        if (field != null) {
                            fields.add(field);
                        }
                    }
                }
                if (classDecl.getBody() != null) {
                    for (Statement stmt : classDecl.getBody().getStatements()) {
                        if (stmt instanceof J.MethodDeclaration
                                && isKotlinPrimaryConstructor((J.MethodDeclaration) stmt)) {
                            for (Statement p : ((J.MethodDeclaration) stmt).getParameters()) {
                                J.VariableDeclarations param = unwrapVariableDeclarations(p);
                                if (param != null) {
                                    fields.add(param);
                                }
                            }
                            continue;
                        }
                        J.VariableDeclarations field = unwrapVariableDeclarations(stmt);
                        if (field != null) {
                            fields.add(field);
                        }
                    }
                }
                return fields;
            }

            /**
             * Compare LST ids rather than references — {@code super.visitVariableDeclarations}
             * can return a new wrapper even when nothing changed semantically, so the visitor's
             * {@code vd} and the class declaration's stored field may diverge by reference
             * while pointing at the same source-level declaration.
             */
            private boolean isFirstFieldInClass(J.ClassDeclaration classDecl,
                                                J.VariableDeclarations vd) {
                List<J.VariableDeclarations> fields = classFields(classDecl);
                return !fields.isEmpty() && fields.get(0).getId().equals(vd.getId());
            }

            private boolean hasExactFieldByName(J.ClassDeclaration classDecl, String fieldName) {
                for (J.VariableDeclarations field : classFields(classDecl)) {
                    if (!field.getVariables().isEmpty()
                            && fieldName.equals(field.getVariables().get(0).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isKotlinPrimaryConstructor(J.MethodDeclaration md) {
                return md.getMarkers().findFirst(PrimaryConstructor.class).isPresent();
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
            // Simple-name fallback: in Kotlin sources the parser may not bind the AF4 stub
            // type, so the FQN match above silently misses entity classes that are obviously
            // entities. Match on the annotation's identifier name as a safety net.
            if (ann.getAnnotationType() instanceof J.Identifier) {
                String name = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
                if ("Aggregate".equals(name) || "EventSourced".equals(name)
                        || "EventSourcedEntity".equals(name)) {
                    return true;
                }
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
            // Same reason as in {@link #isEntityClass}: simple-name fallback for unbound types.
            if (ann.getAnnotationType() instanceof J.Identifier
                    && "EventSourcingHandler".equals(
                            ((J.Identifier) ann.getAnnotationType()).getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the FQN of the declared type of the {@code @AggregateIdentifier} field,
     * or {@code null} when no annotated field is found or its type cannot be resolved
     * to a fully-qualified type (e.g. primitives, unresolved type bindings).
     */
    private static @Nullable String findAggregateIdFieldTypeFqn(J.ClassDeclaration cd) {
        if (cd.getBody() == null) {
            return null;
        }
        for (Statement stmt : cd.getBody().getStatements()) {
            J.VariableDeclarations vd = unwrapVariableDeclarations(stmt);
            if (vd == null
                    || !isAggregateIdentifierField(vd)
                    || vd.getTypeExpression() == null) {
                continue;
            }
            JavaType.FullyQualified ft =
                    TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
            if (ft != null) {
                return ft.getFullyQualifiedName();
            }
        }
        return null;
    }

    /**
     * Returns the {@link J.VariableDeclarations} held by a class-body statement, or
     * {@code null} when the statement is something else (a method, an inner class, ...).
     * Kotlin {@code var}/{@code val} class members surface as {@link K.Property} wrapping
     * a {@link J.VariableDeclarations}, while Java fields are bare
     * {@link J.VariableDeclarations}; this helper hides that difference from callers
     * that just want the underlying field.
     */
    private static J.@Nullable VariableDeclarations unwrapVariableDeclarations(Statement stmt) {
        if (stmt instanceof J.VariableDeclarations) {
            return (J.VariableDeclarations) stmt;
        }
        if (stmt instanceof K.Property) {
            return ((K.Property) stmt).getVariableDeclarations();
        }
        return null;
    }

    private static boolean isAggregateIdentifierField(J.VariableDeclarations vd) {
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), AGGREGATE_IDENTIFIER_AF4)
                    || TypeUtils.isOfClassType(ann.getType(), AGGREGATE_IDENTIFIER_AF5)
                    || (ann.getAnnotationType() instanceof J.Identifier
                            && "AggregateIdentifier".equals(
                                    ((J.Identifier) ann.getAnnotationType()).getSimpleName()))) {
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
            J.VariableDeclarations vd = unwrapVariableDeclarations(stmt);
            if (vd == null) {
                continue;
            }
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
