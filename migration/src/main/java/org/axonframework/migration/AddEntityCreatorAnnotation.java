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
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.Collections;

/**
 * Adds the {@code @EntityCreator} annotation to existing no-arg constructors of classes annotated with either
 * {@code @EventSourcedEntity} (free AF5) or the Spring stereotype {@code @EventSourced}.
 * <p>
 * Axon Framework 5 makes {@code @EntityCreator} mandatory for entity instantiation. The migration guide
 * recommends the no-arg constructor pattern for most existing aggregates: the framework creates an empty instance,
 * and the identifier and other state is initialized in the {@code @EventSourcingHandler} of the creation event —
 * exactly the pattern AF4 users already had with their default-constructor-plus-event-sourcing-handler setup.
 * <p>
 * Idempotent — constructors that already carry {@code @EntityCreator} are left alone.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class AddEntityCreatorAnnotation extends Recipe {

    private static final String ENTITY_CREATOR_FQN = "org.axonframework.eventsourcing.annotation.reflection.EntityCreator";
    private static final String EVENT_SOURCED_ENTITY_FQN = "org.axonframework.eventsourcing.annotation.EventSourcedEntity";
    private static final String EVENT_SOURCED_SPRING_FQN = "org.axonframework.extension.spring.stereotype.EventSourced";
    // Match AF4 stereotypes too, because `ChangeType` doesn't always rebind the
    // J.Annotation's resolved type before this visitor runs in the next cycle —
    // matching by AF4 FQN keeps the recipe single-cycle on raw AF4 input.
    private static final String AF4_AGGREGATE_FQN = "org.axonframework.spring.stereotype.Aggregate";
    private static final String AF4_AGGREGATE_ROOT_FQN = "org.axonframework.modelling.command.AggregateRoot";

    @Override
    public String getDisplayName() {
        return "Add @EntityCreator to no-arg constructors of event-sourced entities";
    }

    @Override
    public String getDescription() {
        return "Annotates existing no-arg constructors with `@EntityCreator` for any class annotated with "
                + "`@EventSourcedEntity` or the Spring `@EventSourced` stereotype. Required by AF5 — the framework "
                + "uses this annotation to instantiate the entity before applying events. Idempotent: skips "
                + "constructors that are already annotated.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                J.ClassDeclaration classDecl = super.visitClassDeclaration(cd, ctx);
                if (!isEventSourcedEntity(classDecl)) {
                    return classDecl;
                }
                return classDecl;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                if (!md.isConstructor() || !md.getParameters().isEmpty()
                        // Java represents an empty parameter list as a single Empty node — accept that too.
                        && !(md.getParameters().size() == 1 && md.getParameters().get(0) instanceof J.Empty)) {
                    return md;
                }
                if (hasEntityCreator(md)) {
                    return md;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !isEventSourcedEntity(enclosing)) {
                    return md;
                }
                // Kotlin property accessors (`get()` / `set(value)`) surface as J.MethodDeclaration
                // with a null return-type expression, which makes `J.MethodDeclaration#isConstructor()`
                // return true. The check above can't distinguish them from a real no-arg
                // constructor, so additionally require the method's simple name to match the
                // enclosing class name — that is the actual Java/Kotlin constructor convention.
                if (!md.getSimpleName().equals(enclosing.getSimpleName())) {
                    return md;
                }
                // Build the @EntityCreator annotation directly via the LST instead of going
                // through JavaTemplate. The template-based path renders the surrounding class
                // body into a Java placeholder during compilation, and Kotlin-only constructs
                // in the body (e.g. `enum class State {}`, `data class`) make that placeholder
                // unparseable as Java — see the failure reproduced by the
                // AddEntityCreatorAnnotationKotlinTest fixture. Direct LST construction
                // sidesteps the Java parser entirely and works uniformly for Java and Kotlin
                // sources. autoFormat afterwards restores the layout JavaTemplate previously
                // produced (e.g. inserting a blank line above the annotation when the
                // preceding member sits directly on the line above).
                J.Annotation entityCreator = buildEntityCreatorAnnotation();
                J.MethodDeclaration annotated = prependAnnotation(md, entityCreator);
                // maybeAddImport AFTER the LST mutation so the visitor's pending-import queue
                // is hit on the post-rewrite cursor.
                maybeAddImport(ENTITY_CREATOR_FQN, null, false);
                return annotated;
            }

            private boolean isEventSourcedEntity(J.ClassDeclaration cd) {
                if (cd.getLeadingAnnotations() == null) {
                    return false;
                }
                for (J.Annotation a : cd.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(a.getType(), EVENT_SOURCED_ENTITY_FQN)
                            || TypeUtils.isOfClassType(a.getType(), EVENT_SOURCED_SPRING_FQN)
                            || TypeUtils.isOfClassType(a.getType(), AF4_AGGREGATE_FQN)
                            || TypeUtils.isOfClassType(a.getType(), AF4_AGGREGATE_ROOT_FQN)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Constructs a fresh {@code @EntityCreator} {@link J.Annotation}. The annotation
             * type is a {@link J.Identifier} bound to the AF5 FQN via
             * {@link JavaType.ShallowClass}; consumers and downstream visitors that match by
             * type get a resolvable binding even though the annotation was synthesized rather
             * than parsed.
             */
            private J.Annotation buildEntityCreatorAnnotation() {
                J.Identifier name = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "EntityCreator",
                        JavaType.ShallowClass.build(ENTITY_CREATOR_FQN),
                        null);
                return new J.Annotation(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        name,
                        null);
            }

            /**
             * Prepends {@code annotation} to {@code md}'s leading-annotation list while keeping
             * the printed layout faithful. {@code md.getPrefix()} is the whitespace before the
             * whole declaration block (annotations included), so it stays untouched — the new
             * annotation slots in immediately under it. What moves is the {@code "\n" + indent}
             * spacing that previously sat between the annotation block and the next element:
             * <ul>
             *   <li>If {@code md} had no leading annotations, that spacing was sitting on the
             *   first modifier (or on the return type / method name when there are no
             *   modifiers, e.g. a Kotlin secondary constructor's {@code constructor} keyword).
             *   We push the spacing onto whichever node now sits immediately after the new
             *   annotation, leaving the new annotation with an empty prefix.</li>
             *   <li>If {@code md} already had leading annotations, the previous first
             *   annotation's prefix was the in-block spacing — we move that onto the new first
             *   annotation, and give the demoted previous first annotation a fresh
             *   {@code "\n" + indent} so it renders on its own line.</li>
             * </ul>
             */
            private J.MethodDeclaration prependAnnotation(J.MethodDeclaration md, J.Annotation annotation) {
                Space methodPrefix = md.getPrefix();
                String whitespace = methodPrefix.getWhitespace();
                String indent = trailingIndent(whitespace);
                Space newlineIndent = Space.format("\n" + indent);

                // Match the historical JavaTemplate-based output: insert a blank line above
                // the new annotation when the declaration sat directly under a sibling
                // (only one newline between them) and is not the first member in its block.
                // Skip the bump if the prefix already carries ≥2 newlines (the blank line is
                // already there) or the declaration is the first statement (no sibling above).
                Space adjustedMethodPrefix = needsBlankLineBump(md, whitespace)
                        ? Space.format("\n" + whitespace)
                        : methodPrefix;

                if (md.getLeadingAnnotations().isEmpty()) {
                    J.MethodDeclaration withAnnotation = md.withPrefix(adjustedMethodPrefix)
                            .withLeadingAnnotations(Collections.singletonList(annotation));
                    if (!withAnnotation.getModifiers().isEmpty()) {
                        return withAnnotation.withModifiers(
                                ListUtils.mapFirst(withAnnotation.getModifiers(),
                                        m -> m.withPrefix(newlineIndent)));
                    }
                    if (withAnnotation.getReturnTypeExpression() != null) {
                        return withAnnotation.withReturnTypeExpression(
                                withAnnotation.getReturnTypeExpression().withPrefix(newlineIndent));
                    }
                    return withAnnotation.withName(withAnnotation.getName().withPrefix(newlineIndent));
                }

                J.Annotation oldFirst = md.getLeadingAnnotations().get(0);
                Space oldFirstPrefix = oldFirst.getPrefix();
                return md.withPrefix(adjustedMethodPrefix)
                        .withLeadingAnnotations(ListUtils.concat(
                                annotation.withPrefix(oldFirstPrefix),
                                ListUtils.mapFirst(md.getLeadingAnnotations(),
                                        first -> first.withPrefix(newlineIndent))));
            }

            private boolean needsBlankLineBump(J.MethodDeclaration md, String whitespace) {
                if (countNewlines(whitespace) >= 2) {
                    return false;
                }
                J.Block block = getCursor().firstEnclosing(J.Block.class);
                if (block == null || block.getStatements().isEmpty()) {
                    return false;
                }
                // Reference equality on the LST id: super.visit may return a structurally
                // different `md`, but `withX` preserves ids, so the first-in-block check is
                // stable across the visit.
                return !block.getStatements().get(0).getId().equals(md.getId());
            }

            private int countNewlines(String s) {
                int count = 0;
                for (int i = 0; i < s.length(); i++) {
                    if (s.charAt(i) == '\n') {
                        count++;
                    }
                }
                return count;
            }

            /**
             * Returns the indent portion of a whitespace string — everything after the last
             * newline, or the entire string if no newline is present. Used to preserve the
             * column at which the existing annotation block sat so the new annotation lines up.
             */
            private String trailingIndent(String whitespace) {
                int idx = whitespace.lastIndexOf('\n');
                return idx < 0 ? whitespace : whitespace.substring(idx + 1);
            }

            private boolean hasEntityCreator(J.MethodDeclaration md) {
                if (md.getLeadingAnnotations() == null) {
                    return false;
                }
                for (J.Annotation a : md.getLeadingAnnotations()) {
                    // Type-based match handles imported annotations whose
                    // bindings have already resolved.
                    if (TypeUtils.isOfClassType(a.getType(), ENTITY_CREATOR_FQN)) {
                        return true;
                    }
                    // Fallback: simple-name match. After the first application
                    // adds `@EntityCreator` via JavaTemplate, the second
                    // cycle's LST may not have the type binding yet — without
                    // this check the visitor would re-add a duplicate
                    // annotation on the next cycle.
                    if (a.getAnnotationType() instanceof J.Identifier
                            && "EntityCreator".equals(((J.Identifier) a.getAnnotationType()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
