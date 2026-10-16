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
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.marker.PrimaryConstructor;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;
import org.jspecify.annotations.Nullable;

import java.util.Collections;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adds {@code @EventTag(key = "<EntitySimpleName>")} to the aggregate-identifier field of every
 * event class used in {@code @EventSourcingHandler} methods.
 * <p>
 * The recipe runs in two phases:
 * <ol>
 *   <li><b>Scan</b> – visits entity classes (annotated with {@code @Aggregate},
 *       {@code @EventSourced}, or {@code @EventSourcedEntity}) and records the event payload type
 *       for every event used in the entity, together with the entity's identifier field name and
 *       the entity's simple class name. Events are discovered from two sources:
 *       (1) the first parameter of each {@code @EventSourcingHandler} method, and
 *       (2) the first argument of every {@code AggregateLifecycle.apply(...)} call site in the
 *       entity body. The second source catches events that are published but never re-sourced in
 *       this entity (a valid AF4 pattern that would otherwise miss the {@code @EventTag}
 *       treatment).</li>
 *   <li><b>Edit</b> – for every event class recorded in the scan, picks the field that should carry
 *       {@code @EventTag(key = "<EntitySimpleName>")}, trying each of the following until one
 *       matches:
 *       <ol>
 *         <li>a field named exactly the entity's identifier field name;</li>
 *         <li>a field whose declared type matches the identifier's type (when known) and whose name
 *         is a case-insensitive suffix relationship of the identifier field name — e.g. entity id
 *         {@code giftCardId} against an event field named {@code id} or {@code cardId};</li>
 *         <li>a field whose name matches (case-insensitively) a {@code @TargetAggregateIdentifier}/
 *         {@code @TargetEntityId} field found on some other class in the same package — typically a
 *         command routing to this entity — with the same type-compatibility check;</li>
 *         <li>otherwise, the first declared field, annotated anyway (so the event isn't left without
 *         a tag) with a {@code // TODO(axon4to5):} comment asking a human reviewer to verify the
 *         choice.</li>
 *       </ol></li>
 * </ol>
 *
 * <p>Child entities declared on a parent via {@code @AggregateMember}/{@code @EntityMember} are
 * followed as well: each event used in a child entity's {@code @EventSourcingHandler} (or
 * {@code apply(...)} call) is tagged with the <b>parent</b> entity's tag, so it is sourced into the
 * parent's stream. This preserves the single event stream an Axon Framework 4 aggregate shared with
 * its members. The same field-matching priority (exact name, then type-plus-name-similarity, then
 * sibling command alias, then first-field fallback with a {@code // TODO(axon4to5):} comment) applies
 * to child events.
 *
 * <p><b>Must run before {@code @AggregateIdentifier} is removed</b> (i.e. before the
 * {@link org.openrewrite.java.RemoveAnnotation} step inside {@code Axon4ToAxon5Modelling}), so
 * that the annotation is still present for the scan.
 *
 * <p><b>What the LLM must still do</b>: verify the selected field is truly the aggregate
 * identifier (especially when the fallback path fires), and adjust {@code key} if the entity
 * simple name differs from the intended tag name.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
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

    // Used only as an additional signal (see Accumulator#packageTargetEntityIdFieldNames) for the
    // matching heuristic below: a command class in the same package as an event class may carry a
    // @TargetAggregateIdentifier/@TargetEntityId field whose name is a better guess for the event's
    // identifier field than the entity's own @AggregateIdentifier field name.
    private static final String TARGET_AGGREGATE_ID_AF4 =
            "org.axonframework.modelling.command.TargetAggregateIdentifier";
    private static final String TARGET_AGGREGATE_ID_AF5_INTERMEDIATE =
            "org.axonframework.modelling.entity.TargetAggregateIdentifier";
    private static final String TARGET_ENTITY_ID_AF5 =
            "org.axonframework.modelling.annotation.TargetEntityId";

    // AF4 @AggregateMember / AF5 @EntityMember, used to follow a parent entity to its child
    // entities so their events are tagged with the PARENT's boundary as well.
    private static final String AGGREGATE_MEMBER_AF4 =
            "org.axonframework.modelling.command.AggregateMember";
    private static final String ENTITY_MEMBER_AF5 =
            "org.axonframework.modelling.entity.annotation.EntityMember";

    private static final String AF4_AGGREGATE_LIFECYCLE =
            "org.axonframework.modelling.command.AggregateLifecycle";
    private static final String AF5_AGGREGATE_LIFECYCLE =
            "org.axonframework.modelling.entity.AggregateLifecycle";
    private static final MethodMatcher APPLY_AF4 =
            new MethodMatcher(AF4_AGGREGATE_LIFECYCLE + " apply(..)");
    private static final MethodMatcher APPLY_AF5 =
            new MethodMatcher(AF5_AGGREGATE_LIFECYCLE + " apply(..)");

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
            /**
             * FQN of the entity's {@code @AggregateIdentifier} field type, or {@code null} when it
             * could not be resolved. Used by the type-plus-name-similarity matching heuristic to
             * avoid false positives when falling back from an exact field-name match.
             */
            final @Nullable String idFieldTypeFqn;

            EventTagTarget(String idFieldName, String tagKey, @Nullable String idFieldTypeFqn) {
                this.idFieldName = idFieldName;
                this.tagKey = tagKey;
                this.idFieldTypeFqn = idFieldTypeFqn;
            }
        }

        final Map<String, EventTagTarget> targets = new HashMap<>();

        /**
         * Maps a package name to the simple names of fields annotated with
         * {@code @TargetAggregateIdentifier}/{@code @TargetEntityId} on any class found in that
         * package (typically a command class). Used as an additional, lower-priority matching
         * signal: when an event class has no field matching the entity's identifier field by exact
         * name or by type-plus-name-similarity, a field whose name matches a sibling command's
         * routing-identifier field (and whose type still matches the entity's identifier type) is
         * preferred over the "first field" fallback.
         */
        final Map<String, Set<String>> packageTargetEntityIdFieldNames = new HashMap<>();

        /**
         * Maps a child-entity class FQN (declared via {@code @AggregateMember}/{@code @EntityMember}
         * on a parent entity) to the PARENT entity's tag boundary. Child events are tagged with the
         * parent's tag so they are sourced into the parent's stream.
         */
        final Map<String, EventTagTarget> memberChildBoundary = new HashMap<>();

        /**
         * Maps any class FQN to the non-framework event types it uses, via {@code @EventSourcingHandler}
         * or {@code AggregateLifecycle.apply(...)}. Populated for every class so that child entities
         * (which are not themselves entity classes) can be reconciled against their parent's boundary.
         */
        final Map<String, List<String>> classEventTypes = new HashMap<>();

        /** Guards {@link AddEventTagAnnotation#reconcileMemberEvents} so it runs once. */
        boolean reconciled = false;
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
                // Record the events used in every class (entity or not). Child entities declared
                // via @AggregateMember/@EntityMember are not entity classes themselves, so their
                // events are captured here and later attributed to the parent's tag boundary.
                if (classDecl.getType() != null) {
                    List<String> events = collectClassEventTypes(classDecl);
                    if (!events.isEmpty()) {
                        acc.classEventTypes
                           .computeIfAbsent(classDecl.getType().getFullyQualifiedName(),
                                            k -> new java.util.ArrayList<>())
                           .addAll(events);
                    }
                }

                // Record the simple names of any @TargetAggregateIdentifier/@TargetEntityId fields
                // on THIS class (typically a command class), keyed by package. This is a weaker,
                // additional signal the edit phase consults when an event's identifier field can't
                // be matched by exact name or by type-plus-name-similarity against the entity's own
                // @AggregateIdentifier field name.
                if (classDecl.getType() != null) {
                    List<String> targetIdFieldNames = findTargetEntityIdFieldNames(classDecl);
                    if (!targetIdFieldNames.isEmpty()) {
                        String pkg = packageOf(classDecl.getType().getFullyQualifiedName());
                        acc.packageTargetEntityIdFieldNames
                           .computeIfAbsent(pkg, k -> new HashSet<>())
                           .addAll(targetIdFieldNames);
                    }
                }

                if (!isEntityClass(classDecl)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                String entitySimpleName = classDecl.getSimpleName();
                String idFieldName = findAggregateIdFieldName(classDecl);
                if (idFieldName == null) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }
                String idFieldTypeFqn = findAggregateIdFieldTypeFqn(classDecl);

                // Publish the @AggregateIdentifier field's declared type so
                // ConfigureEventSourcedAnnotation can populate @EventSourced(idType=...)
                // even when it runs after RemoveAnnotation has stripped @AggregateIdentifier.
                if (classDecl.getType() != null && idFieldTypeFqn != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> shared =
                            (Map<String, String>) ctx.getMessage(SHARED_ID_TYPES_KEY);
                    if (shared == null) {
                        shared = new HashMap<>();
                        ctx.putMessage(SHARED_ID_TYPES_KEY, shared);
                    }
                    shared.put(classDecl.getType().getFullyQualifiedName(), idFieldTypeFqn);
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
                                    new Accumulator.EventTagTarget(
                                            idFieldName, entitySimpleName, idFieldTypeFqn));
                }

                // Also collect event types from `AggregateLifecycle.apply(...)` call sites
                // anywhere in the entity body. Catches events that are published but not
                // re-sourced in this entity (no matching @EventSourcingHandler) — a valid
                // AF4 pattern that would otherwise miss the @EventTag treatment.
                final String capturedIdFieldName = idFieldName;
                final String capturedEntitySimpleName = entitySimpleName;
                final String capturedIdFieldTypeFqn = idFieldTypeFqn;
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi,
                                                                    ExecutionContext c) {
                        J.MethodInvocation invocation = super.visitMethodInvocation(mi, c);
                        if (!APPLY_AF4.matches(invocation) && !APPLY_AF5.matches(invocation)) {
                            return invocation;
                        }
                        if (invocation.getArguments().isEmpty()
                                || invocation.getArguments().get(0) instanceof J.Empty) {
                            return invocation;
                        }
                        Expression payload = invocation.getArguments().get(0);
                        JavaType.FullyQualified eventType =
                                TypeUtils.asFullyQualified(payload.getType());
                        if (eventType == null
                                || eventType.getFullyQualifiedName().startsWith("org.axonframework")) {
                            return invocation;
                        }
                        acc.targets.putIfAbsent(eventType.getFullyQualifiedName(),
                                                new Accumulator.EventTagTarget(
                                                        capturedIdFieldName,
                                                        capturedEntitySimpleName,
                                                        capturedIdFieldTypeFqn));
                        return invocation;
                    }
                }.visit(classDecl, ctx);

                // Follow @AggregateMember/@EntityMember fields to their child entity types, and
                // record that those children belong to THIS entity's tag boundary. Their events are
                // reconciled into `targets` before the edit phase, so a child event is tagged with
                // the parent's tag and thus sourced into the parent's stream (as in Axon Framework 4,
                // where an aggregate and its members shared one event stream).
                for (String childFqn : findMemberChildFqns(classDecl)) {
                    acc.memberChildBoundary.putIfAbsent(
                            childFqn,
                            new Accumulator.EventTagTarget(idFieldName, entitySimpleName, idFieldTypeFqn));
                }

                return super.visitClassDeclaration(classDecl, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        reconcileMemberEvents(acc);
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

                // Resolve, once per class, which field is the best match for the entity's
                // identifier — trying an exact name match first, then a type-plus-name-similarity
                // heuristic, then a sibling command's @TargetEntityId field name, and only then
                // falling back to the first field (flagged for human review). See
                // #resolveIdField for the full priority order.
                ResolvedIdField resolved = resolveIdField(enclosingClass, target, acc, classFqn);
                if (resolved == null || !resolved.field.getId().equals(vd.getId())) {
                    return vd;
                }
                if (resolved.needsReview) {
                    // Fallback — first field; mark for LLM review. We still annotate it because
                    // leaving an event without @EventTag would cause a runtime failure; the LLM
                    // must verify the field choice.
                    return annotateWithEventTag(vd, target.tagKey,
                                                " TODO(axon4to5): verify this is the aggregate-id field");
                }
                return annotateWithEventTag(vd, target.tagKey, null);
            }

            /** The field chosen by {@link #resolveIdField}, and whether that choice needs review. */
            private final class ResolvedIdField {
                final J.VariableDeclarations field;
                final boolean needsReview;

                ResolvedIdField(J.VariableDeclarations field, boolean needsReview) {
                    this.field = field;
                    this.needsReview = needsReview;
                }
            }

            /**
             * Picks the field in {@code enclosingClass} that should carry the {@code @EventTag},
             * trying each of the following in order and returning the first match:
             * <ol>
             *   <li><b>Exact name match</b> — a field named exactly {@code target.idFieldName}.</li>
             *   <li><b>Type + name-similarity match</b> — a field whose declared type matches the
             *   entity identifier's type (when known) and whose name is a case-insensitive suffix
             *   relationship of {@code target.idFieldName} (e.g. entity id {@code giftCardId} and
             *   event field {@code id}, or event field {@code cardId}).</li>
             *   <li><b>Sibling command alias match</b> — a field whose name matches (case-insensitive)
             *   the name of a {@code @TargetAggregateIdentifier}/{@code @TargetEntityId} field found
             *   on some other class in the same package (typically a command class routing to this
             *   entity), and whose type still matches the entity identifier's type when known.</li>
             *   <li><b>Fallback</b> — the first declared field, flagged {@code needsReview}.</li>
             * </ol>
             * Returns {@code null} when the class has no fields at all.
             */
            private @Nullable ResolvedIdField resolveIdField(J.ClassDeclaration enclosingClass,
                                                              Accumulator.EventTagTarget target,
                                                              Accumulator acc,
                                                              String classFqn) {
                List<J.VariableDeclarations> fields = classFields(enclosingClass);
                if (fields.isEmpty()) {
                    return null;
                }
                for (J.VariableDeclarations field : fields) {
                    if (target.idFieldName.equals(fieldSimpleName(field))) {
                        return new ResolvedIdField(field, false);
                    }
                }
                for (J.VariableDeclarations field : fields) {
                    if (isTypeAndNameSimilarMatch(field, target)) {
                        return new ResolvedIdField(field, false);
                    }
                }
                Set<String> aliases = acc.packageTargetEntityIdFieldNames.get(packageOf(classFqn));
                if (aliases != null) {
                    for (J.VariableDeclarations field : fields) {
                        String name = fieldSimpleName(field);
                        if (name != null && containsIgnoreCase(aliases, name)
                                && typeCompatible(field, target)) {
                            return new ResolvedIdField(field, false);
                        }
                    }
                }
                return new ResolvedIdField(fields.get(0), true);
            }

            private boolean isTypeAndNameSimilarMatch(J.VariableDeclarations field,
                                                      Accumulator.EventTagTarget target) {
                String name = fieldSimpleName(field);
                return name != null
                        && nameIsSimilar(name, target.idFieldName)
                        && typeCompatible(field, target);
            }

            /**
             * A candidate field name is "similar" to the entity's identifier field name when the
             * identifier field name ends with it, case-insensitively — e.g. entity id
             * {@code giftCardId} and event field {@code id} or {@code cardId}. Intentionally
             * one-directional: an event field like {@code transactionId} should not match an entity
             * identifier simply named {@code id}, since that would tag an unrelated identifier.
             */
            private boolean nameIsSimilar(String candidateFieldName, String idFieldName) {
                return idFieldName.toLowerCase(Locale.ROOT)
                                  .endsWith(candidateFieldName.toLowerCase(Locale.ROOT));
            }

            /**
             * Returns {@code true} when {@code field}'s declared type cannot be compared (unresolved,
             * or the entity identifier's type is unknown) or matches {@code target.idFieldTypeFqn}.
             * Unresolved types are treated as compatible so the heuristic still fires on synthetic
             * test sources without a full classpath.
             */
            private boolean typeCompatible(J.VariableDeclarations field, Accumulator.EventTagTarget target) {
                if (target.idFieldTypeFqn == null || field.getTypeExpression() == null) {
                    return true;
                }
                JavaType fieldRawType = field.getTypeExpression().getType();
                if (fieldRawType instanceof JavaType.Primitive) {
                    // The entity identifier resolved to a reference type (idFieldTypeFqn is set),
                    // so a primitive-typed field can never be a match — treat unconditionally as
                    // incompatible instead of the permissive "can't verify" default below.
                    return false;
                }
                JavaType.FullyQualified fieldType = TypeUtils.asFullyQualified(fieldRawType);
                return fieldType == null || target.idFieldTypeFqn.equals(fieldType.getFullyQualifiedName());
            }

            private boolean containsIgnoreCase(Set<String> names, String name) {
                for (String candidate : names) {
                    if (candidate.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
                return false;
            }

            private @Nullable String fieldSimpleName(J.VariableDeclarations field) {
                return field.getVariables().isEmpty() ? null : field.getVariables().get(0).getSimpleName();
            }

            private J.VariableDeclarations annotateWithEventTag(J.VariableDeclarations vd,
                                                                 String tagKey,
                                                                 @Nullable String todoComment) {
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
                    return todoComment == null ? annotated : appendTrailingComment(annotated, todoComment);
                }
                J.VariableDeclarations annotated = JavaTemplate.builder(
                                "@EventTag(key = \"" + tagKey + "\")")
                        .imports(EVENT_TAG_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), vd.getCoordinates().addAnnotation((a, b) -> 0));
                maybeAddImport(EVENT_TAG_FQN, null, false);
                J.VariableDeclarations onOwnLine = forceAnnotationOnOwnLine(annotated);
                return todoComment == null ? onOwnLine : appendTrailingComment(onOwnLine, todoComment);
            }

            /**
             * Appends {@code commentText} as a trailing {@code //} comment right after {@code vd}'s
             * last leading annotation, on the same line, by converting the newline+indent prefix of
             * whatever node follows the annotation (a modifier, or the type expression) into
             * {@code " " + comment + <original newline+indent>}.
             */
            private J.VariableDeclarations appendTrailingComment(J.VariableDeclarations vd, String commentText) {
                if (!vd.getModifiers().isEmpty()) {
                    return vd.withModifiers(ListUtils.mapFirst(vd.getModifiers(),
                            m -> m.withPrefix(withTrailingComment(m.getPrefix(), commentText))));
                }
                if (vd.getTypeExpression() != null) {
                    return vd.withTypeExpression(vd.getTypeExpression().withPrefix(
                            withTrailingComment(vd.getTypeExpression().getPrefix(), commentText)));
                }
                return vd;
            }

            private Space withTrailingComment(Space prefix, String commentText) {
                String suffix = prefix.getWhitespace().isEmpty() ? "\n" : prefix.getWhitespace();
                TextComment comment = new TextComment(false, commentText, suffix, Markers.EMPTY);
                return prefix.withWhitespace(" ")
                             .withComments(ListUtils.concat(prefix.getComments(), comment));
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

    /**
     * Returns the simple names of any fields, Java record components, or Kotlin primary-constructor
     * parameters on {@code cd} annotated with {@code @TargetAggregateIdentifier} (AF4) or its AF5
     * successors {@code @TargetAggregateIdentifier} (post-{@code ChangePackage}) /
     * {@code @TargetEntityId} (post-rename). Used to populate
     * {@link Accumulator#packageTargetEntityIdFieldNames}: a command class routing to an entity by
     * one of these fields is a useful, package-scoped hint for the entity's identifier field name
     * on the entity's own events, when that name doesn't match the entity's
     * {@code @AggregateIdentifier} field directly.
     */
    private static List<String> findTargetEntityIdFieldNames(J.ClassDeclaration cd) {
        List<String> names = new java.util.ArrayList<>();
        if (cd.getPrimaryConstructor() != null) {
            for (Statement stmt : cd.getPrimaryConstructor()) {
                addIfTargetEntityIdField(unwrapVariableDeclarations(stmt), names);
            }
        }
        if (cd.getBody() != null) {
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration
                        && ((J.MethodDeclaration) stmt).getMarkers()
                                                        .findFirst(PrimaryConstructor.class)
                                                        .isPresent()) {
                    for (Statement param : ((J.MethodDeclaration) stmt).getParameters()) {
                        addIfTargetEntityIdField(unwrapVariableDeclarations(param), names);
                    }
                    continue;
                }
                addIfTargetEntityIdField(unwrapVariableDeclarations(stmt), names);
            }
        }
        return names;
    }

    private static void addIfTargetEntityIdField(J.@Nullable VariableDeclarations vd, List<String> out) {
        if (vd == null || vd.getVariables().isEmpty()) {
            return;
        }
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), TARGET_AGGREGATE_ID_AF4)
                    || TypeUtils.isOfClassType(ann.getType(), TARGET_AGGREGATE_ID_AF5_INTERMEDIATE)
                    || TypeUtils.isOfClassType(ann.getType(), TARGET_ENTITY_ID_AF5)
                    || (ann.getAnnotationType() instanceof J.Identifier
                            && ("TargetAggregateIdentifier".equals(
                                        ((J.Identifier) ann.getAnnotationType()).getSimpleName())
                                    || "TargetEntityId".equals(
                                        ((J.Identifier) ann.getAnnotationType()).getSimpleName())))) {
                out.add(vd.getVariables().get(0).getSimpleName());
                return;
            }
        }
    }

    /** Returns the package portion of a fully-qualified class name, or {@code ""} for the default package. */
    private static String packageOf(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx < 0 ? "" : fqn.substring(0, idx);
    }

    /**
     * Attributes every child-entity event to its parent's tag boundary. For each child discovered
     * via {@code @AggregateMember}/{@code @EntityMember}, each event the child uses is added to
     * {@link Accumulator#targets} with the parent's {@code idFieldName} and {@code tagKey}, unless
     * the event already has a target. Runs once, after the scan has populated the accumulator.
     */
    private static void reconcileMemberEvents(Accumulator acc) {
        if (acc.reconciled) {
            return;
        }
        acc.reconciled = true;
        for (Map.Entry<String, Accumulator.EventTagTarget> entry : acc.memberChildBoundary.entrySet()) {
            List<String> events = acc.classEventTypes.get(entry.getKey());
            if (events == null) {
                continue;
            }
            for (String eventFqn : events) {
                acc.targets.putIfAbsent(eventFqn, entry.getValue());
            }
        }
    }

    /**
     * Collects the non-framework event types used in {@code classDecl}, from both the first
     * parameter of each {@code @EventSourcingHandler} method and the first argument of every
     * {@code AggregateLifecycle.apply(...)} call in the class body.
     */
    private static List<String> collectClassEventTypes(J.ClassDeclaration classDecl) {
        List<String> events = new java.util.ArrayList<>();
        if (classDecl.getBody() == null) {
            return events;
        }
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
            JavaType.FullyQualified eventType =
                    TypeUtils.asFullyQualified(firstParam.getTypeExpression().getType());
            if (eventType != null
                    && !eventType.getFullyQualifiedName().startsWith("org.axonframework")) {
                events.add(eventType.getFullyQualifiedName());
            }
        }
        new JavaIsoVisitor<List<String>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, List<String> collected) {
                J.MethodInvocation invocation = super.visitMethodInvocation(mi, collected);
                if (!APPLY_AF4.matches(invocation) && !APPLY_AF5.matches(invocation)) {
                    return invocation;
                }
                if (invocation.getArguments().isEmpty()
                        || invocation.getArguments().get(0) instanceof J.Empty) {
                    return invocation;
                }
                JavaType.FullyQualified eventType =
                        TypeUtils.asFullyQualified(invocation.getArguments().get(0).getType());
                if (eventType != null
                        && !eventType.getFullyQualifiedName().startsWith("org.axonframework")
                        && !collected.contains(eventType.getFullyQualifiedName())) {
                    collected.add(eventType.getFullyQualifiedName());
                }
                return invocation;
            }
        }.visit(classDecl, events);
        return events;
    }

    /**
     * Returns the FQNs of the child entity types declared on {@code cd} via
     * {@code @AggregateMember}/{@code @EntityMember} fields. For {@code List<Child>},
     * {@code Map<K, Child>}, or {@code Optional<Child>} fields the element (last type parameter) is
     * returned.
     */
    private static List<String> findMemberChildFqns(J.ClassDeclaration cd) {
        List<String> result = new java.util.ArrayList<>();
        if (cd.getBody() == null) {
            return result;
        }
        for (Statement stmt : cd.getBody().getStatements()) {
            J.VariableDeclarations vd = unwrapVariableDeclarations(stmt);
            if (vd == null || !isEntityMemberField(vd)) {
                continue;
            }
            String childFqn = memberChildTypeFqn(vd);
            if (childFqn != null
                    && !childFqn.startsWith("org.axonframework")
                    && !childFqn.startsWith("java.")) {
                result.add(childFqn);
            }
        }
        return result;
    }

    private static boolean isEntityMemberField(J.VariableDeclarations vd) {
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), AGGREGATE_MEMBER_AF4)
                    || TypeUtils.isOfClassType(ann.getType(), ENTITY_MEMBER_AF5)) {
                return true;
            }
            if (ann.getAnnotationType() instanceof J.Identifier) {
                String name = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
                if ("AggregateMember".equals(name) || "EntityMember".equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolves the child entity FQN from an {@code @AggregateMember}/{@code @EntityMember} field's
     * declared type, unwrapping the last type parameter for collection / map / optional fields.
     */
    private static @Nullable String memberChildTypeFqn(J.VariableDeclarations vd) {
        if (vd.getTypeExpression() == null) {
            return null;
        }
        JavaType type = vd.getTypeExpression().getType();
        if (type instanceof JavaType.Parameterized) {
            List<JavaType> typeParameters = ((JavaType.Parameterized) type).getTypeParameters();
            if (!typeParameters.isEmpty()) {
                JavaType.FullyQualified element =
                        TypeUtils.asFullyQualified(typeParameters.get(typeParameters.size() - 1));
                if (element != null) {
                    return element.getFullyQualifiedName();
                }
            }
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq == null ? null : fq.getFullyQualifiedName();
    }
}
