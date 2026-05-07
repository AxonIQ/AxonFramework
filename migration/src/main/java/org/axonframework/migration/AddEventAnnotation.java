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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans for methods annotated with {@code @EventSourcingHandler} and annotates their event payload
 * types with {@code @Event}.
 * <p>
 * If the event class carried an AF4 {@code @Revision("x")} annotation, it is removed and the
 * version is forwarded into {@code @Event(version = "x")}, preserving the serialization contract.
 * Event classes without {@code @Revision} receive a bare {@code @Event} (defaults to
 * {@code version = "0.0.1"} per the AF5 specification).
 * <p>
 * Both AF4 ({@code org.axonframework.eventsourcing.EventSourcingHandler}) and AF5
 * ({@code org.axonframework.eventsourcing.annotation.EventSourcingHandler}) FQNs are matched so
 * the recipe is safe to run before or after {@code Axon4ToAxon5EventSourcing}.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class AddEventAnnotation extends ScanningRecipe<AddEventAnnotation.Accumulator> {

    private static final String ESH_AF4 = "org.axonframework.eventsourcing.EventSourcingHandler";
    private static final String ESH_AF5 = "org.axonframework.eventsourcing.annotation.EventSourcingHandler";
    private static final String EVENT_FQN = "org.axonframework.messaging.eventhandling.annotation.Event";
    private static final String REVISION_FQN = "org.axonframework.serialization.Revision";
    // The Axon4ToAxon5Conversion recipe renames `org.axonframework.serialization.*` to
    // `org.axonframework.conversion.*`. When it runs in the same cycle as this recipe, the
    // @Revision import lives at the renamed path — so we must clear both potential locations.
    private static final String REVISION_FQN_RENAMED = "org.axonframework.conversion.Revision";

    public static class Accumulator {

        final Set<String> eventTypeFqns = new HashSet<>();
    }

    @Override
    public String getDisplayName() {
        return "Add @Event to event payload classes";
    }

    @Override
    public String getDescription() {
        return "Scans @EventSourcingHandler methods and annotates their event parameter types with "
                + "@Event. Migrates @Revision(\"x\") on the class to @Event(version = \"x\"), "
                + "removing the now-obsolete @Revision annotation.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                              ExecutionContext ctx) {
                if (isEventSourcingHandler(method)) {
                    List<Statement> params = method.getParameters();
                    if (!params.isEmpty() && params.get(0) instanceof J.VariableDeclarations) {
                        J.VariableDeclarations firstParam = (J.VariableDeclarations) params.get(0);
                        if (firstParam.getTypeExpression() != null) {
                            JavaType.FullyQualified fqType = TypeUtils.asFullyQualified(
                                    firstParam.getTypeExpression().getType());
                            if (fqType != null
                                    && !fqType.getFullyQualifiedName().startsWith("org.axonframework")) {
                                acc.eventTypeFqns.add(fqType.getFullyQualifiedName());
                            }
                        }
                    }
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            private boolean isEventSourcingHandler(J.MethodDeclaration method) {
                for (J.Annotation ann : method.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), ESH_AF4)
                            || TypeUtils.isOfClassType(ann.getType(), ESH_AF5)) {
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
                        || !acc.eventTypeFqns.contains(classDecl.getType().getFullyQualifiedName())
                        || hasAnnotation(classDecl, EVENT_FQN)) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }

                String revisionValue = findRevisionValue(classDecl);
                String annotationText = revisionValue != null
                        ? "@Event(version = \"" + revisionValue + "\")"
                        : "@Event";

                // Add @Event FIRST. JavaTemplate.apply walks the visitor's cursor (which still
                // references the un-modified `classDecl`), so removing @Revision beforehand
                // would be silently discarded by the apply.
                J.ClassDeclaration annotated = JavaTemplate.builder(annotationText)
                        .imports(EVENT_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), classDecl.getCoordinates().addAnnotation((a, b) -> 0));
                maybeAddImport(EVENT_FQN, null, false);

                if (revisionValue != null) {
                    annotated = removeClassAnnotation(annotated, REVISION_FQN);
                    maybeRemoveImport(REVISION_FQN);
                    maybeRemoveImport(REVISION_FQN_RENAMED);
                }
                return super.visitClassDeclaration(annotated, ctx);
            }
        };
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

    /**
     * Returns the string value of {@code @Revision("value")} if the class carries that annotation,
     * or {@code null} if it does not.
     */
    private static String findRevisionValue(J.ClassDeclaration cd) {
        for (J.Annotation ann : cd.getLeadingAnnotations()) {
            boolean isRevision = TypeUtils.isOfClassType(ann.getType(), REVISION_FQN)
                    || (ann.getAnnotationType() instanceof J.Identifier
                            && "Revision".equals(
                                    ((J.Identifier) ann.getAnnotationType()).getSimpleName()));
            if (!isRevision) {
                continue;
            }
            if (ann.getArguments() != null && !ann.getArguments().isEmpty()) {
                J arg = ann.getArguments().get(0);
                if (arg instanceof J.Literal) {
                    Object val = ((J.Literal) arg).getValue();
                    if (val != null) {
                        return val.toString();
                    }
                }
                // Assignment form: @Revision(value = "x")
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getAssignment() instanceof J.Literal) {
                        Object val = ((J.Literal) assignment.getAssignment()).getValue();
                        if (val != null) {
                            return val.toString();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static J.ClassDeclaration removeClassAnnotation(J.ClassDeclaration cd, String fqn) {
        List<J.Annotation> originals = cd.getLeadingAnnotations();
        List<J.Annotation> remaining = new ArrayList<>();
        boolean firstWasRemoved = false;
        org.openrewrite.java.tree.Space removedFirstPrefix = null;
        for (int i = 0; i < originals.size(); i++) {
            J.Annotation ann = originals.get(i);
            boolean matches = TypeUtils.isOfClassType(ann.getType(), fqn)
                    || (ann.getAnnotationType() instanceof J.Identifier
                            && fqn.endsWith("."
                                    + ((J.Identifier) ann.getAnnotationType()).getSimpleName()));
            if (matches) {
                if (i == 0) {
                    firstWasRemoved = true;
                    removedFirstPrefix = ann.getPrefix();
                }
            } else {
                remaining.add(ann);
            }
        }
        if (remaining.size() == originals.size()) {
            return cd;
        }
        // When the removed annotation was the first one, its prefix carried the leading blank
        // line / indent used to separate the annotation block from the imports above. Transfer
        // that prefix to whatever becomes the new first annotation so we don't leave an extra
        // blank line behind.
        if (firstWasRemoved && !remaining.isEmpty() && removedFirstPrefix != null) {
            J.Annotation newFirst = remaining.get(0).withPrefix(removedFirstPrefix);
            remaining.set(0, newFirst);
        }
        return cd.withLeadingAnnotations(remaining);
    }
}
