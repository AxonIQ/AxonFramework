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
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

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
 * @author Axon Framework Team
 * @since 5.2.0
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
                J.MethodDeclaration annotated = JavaTemplate.builder("@EntityCreator")
                        .imports(ENTITY_CREATOR_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), md.getCoordinates().addAnnotation((a, b) -> 0));
                // maybeAddImport AFTER the template so the visitor's pending-
                // import queue is hit on the post-rewrite cursor.
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
