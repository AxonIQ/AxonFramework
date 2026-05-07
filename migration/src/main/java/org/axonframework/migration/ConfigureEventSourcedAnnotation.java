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
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Adds explicit {@code tagKey} and {@code idType} attributes to {@code @EventSourced} annotations
 * that do not yet configure them.
 * <p>
 * The {@code tagKey} is set to the entity's simple class name — matching the framework default
 * (an empty {@code tagKey} resolves to the simple class name at runtime, but making it explicit
 * here ensures it is visible for review and stays stable even if the class is later renamed).
 * <p>
 * The {@code idType} is deduced from the type of the field annotated with {@code @AggregateIdentifier}
 * (the AF4 marker for the aggregate's identifier). The recipe runs in two phases:
 * <ol>
 *   <li><b>Scan</b> – walks every field annotated with {@code @AggregateIdentifier} (matching either
 *       the AF4 FQN, the post-{@code ChangePackage} AF5 FQN, or the simple name as a fallback) and
 *       records {@code enclosingClassFqn → fieldTypeFqn}.</li>
 *   <li><b>Edit</b> – for every {@code @EventSourced} annotation without a {@code tagKey} attribute,
 *       generates {@code @EventSourced(tagKey = "<EntitySimpleName>", idType = <ResolvedType>.class)}.
 *       If no {@code @AggregateIdentifier} field was discovered for the enclosing class, falls back
 *       to {@code Object.class} with a {@code TODO #LLM} comment so the developer notices and
 *       supplies the correct type manually.</li>
 * </ol>
 * <p>
 * <b>When to run:</b> while the AF4 {@code @AggregateIdentifier} annotation is still present on the
 * source (i.e. before {@code Axon4ToAxon5Modelling} strips it via {@link
 * org.openrewrite.java.RemoveAnnotation}). The umbrella recipe orders {@code Axon4ToAxon5SpringExtension}
 * (which runs this step) ahead of {@code Axon4ToAxon5Modelling} for that reason. The visitor itself
 * targets the AF5 {@code @EventSourced} FQN, so callers must also run after the AF4 Spring stereotype
 * {@code @Aggregate} → AF5 {@code @EventSourced} rename.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class ConfigureEventSourcedAnnotation
        extends ScanningRecipe<ConfigureEventSourcedAnnotation.Accumulator> {

    private static final String EVENT_SOURCED_AF5 =
            "org.axonframework.extension.spring.stereotype.EventSourced";

    /** AF4 FQN — {@code @AggregateIdentifier} before {@code ChangePackage} runs. */
    private static final String AGGREGATE_IDENTIFIER_AF4 =
            "org.axonframework.modelling.command.AggregateIdentifier";
    /** AF5 FQN — {@code @AggregateIdentifier} after {@code ChangePackage} but before removal. */
    private static final String AGGREGATE_IDENTIFIER_AF5 =
            "org.axonframework.modelling.entity.AggregateIdentifier";

    private static final String TODO_COMMENT =
            " /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */";
    private static final String TODO_FALLBACK = "Object.class" + TODO_COMMENT;
    private static final String TODO_FALLBACK_KOTLIN = "Object::class.java" + TODO_COMMENT;

    /** Records {@code enclosingClassFqn → idTypeFqn} for every {@code @AggregateIdentifier} field. */
    public static class Accumulator {
        final Map<String, String> idTypesByClass = new HashMap<>();
    }

    @Override
    public String getDisplayName() {
        return "Add explicit tagKey and idType to @EventSourced";
    }

    @Override
    public String getDescription() {
        return "Adds explicit tagKey = \"<EntitySimpleName>\" and idType = <ResolvedType>.class to "
                + "@EventSourced annotations that have no tagKey set. The tagKey is derived from "
                + "the class simple name (matching the AF5 default). The idType is deduced from the "
                + "type of the field annotated with @AggregateIdentifier in AF4. When that field is "
                + "absent (e.g. POJO aggregate without an explicit identifier field) the idType "
                + "falls back to Object.class and is flagged with a TODO #LLM comment.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar,
                                                                    ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVar, ctx);
                if (!hasAggregateIdentifier(vd)) {
                    return vd;
                }
                if (vd.getTypeExpression() == null) {
                    return vd;
                }
                JavaType.FullyQualified fieldType =
                        TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
                if (fieldType == null) {
                    return vd;
                }
                J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (cd == null || cd.getType() == null) {
                    return vd;
                }
                acc.idTypesByClass.put(cd.getType().getFullyQualifiedName(),
                                       fieldType.getFullyQualifiedName());
                return vd;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<>() {

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation ann = super.visitAnnotation(annotation, ctx);

                if (!isEventSourced(ann)) {
                    return ann;
                }
                if (hasTagKey(ann)) {
                    return ann;
                }

                // AF4 `@Aggregate(snapshotTriggerDefinition = "…")` survives the
                // `ChangeType` rename as `@EventSourced(snapshotTriggerDefinition = "…")`,
                // but AF5 `@EventSourced` has no such attribute. The replace below would
                // silently drop it; capture the value so we can emit a TODO comment that
                // preserves the intent for a human reviewer.
                String snapshotTriggerSource = findArgumentSource(ann, "snapshotTriggerDefinition");

                J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (cd == null) {
                    return ann;
                }

                String tagKey = cd.getSimpleName();
                String idTypeFqn = null;
                if (cd.getType() != null) {
                    String classFqn = cd.getType().getFullyQualifiedName();
                    idTypeFqn = acc.idTypesByClass.get(classFqn);
                    if (idTypeFqn == null) {
                        // Fallback: consume the cross-recipe map populated by
                        // AddEventTagAnnotation (which scans @AggregateIdentifier
                        // before Axon4ToAxon5Modelling strips it).
                        @SuppressWarnings("unchecked")
                        Map<String, String> shared = (Map<String, String>) ctx
                                .getMessage(AddEventTagAnnotation.SHARED_ID_TYPES_KEY);
                        if (shared != null) {
                            idTypeFqn = shared.get(classFqn);
                        }
                    }
                }

                // Kotlin sources need `X::class.java` rather than Java's `X.class` for class
                // literals. Detect the source language so the same recipe can emit either
                // syntax — both routes flow through their language-native template engine
                // (JavaTemplate / KotlinTemplate) so the resulting LST is properly typed.
                boolean kotlin = getCursor().firstEnclosing(SourceFile.class)
                        instanceof K.CompilationUnit;

                String idTypeExpr;
                String idTypeImport;
                if (idTypeFqn == null) {
                    idTypeExpr = kotlin ? TODO_FALLBACK_KOTLIN : TODO_FALLBACK;
                    idTypeImport = null;
                } else {
                    String simpleName = idTypeFqn.substring(idTypeFqn.lastIndexOf('.') + 1);
                    idTypeExpr = simpleName + (kotlin ? "::class.java" : ".class");
                    // java.lang types are auto-imported.
                    idTypeImport = idTypeFqn.startsWith("java.lang.") ? null : idTypeFqn;
                }

                String newAnnotationText = "@EventSourced(tagKey = \"" + tagKey
                        + "\", idType = " + idTypeExpr + ")";

                J.Annotation result = kotlin
                        ? applyKotlinTemplate(newAnnotationText, idTypeImport, ann)
                        : applyJavaTemplate(newAnnotationText, idTypeImport, ann);

                if (idTypeImport != null) {
                    maybeAddImport(idTypeImport);
                }
                if (snapshotTriggerSource != null) {
                    result = prependLineComment(result,
                                                " TODO #LLM: reconfigure snapshot trigger "
                                                        + "(AF4 had snapshotTriggerDefinition = "
                                                        + snapshotTriggerSource + ")");
                }
                return result;
            }

            private J.Annotation applyJavaTemplate(String annotationText,
                                                    @Nullable String idTypeImport,
                                                    J.Annotation target) {
                JavaTemplate.Builder builder = JavaTemplate.builder(annotationText)
                        .imports(EVENT_SOURCED_AF5)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
                if (idTypeImport != null) {
                    builder = builder.imports(idTypeImport);
                }
                return builder.build().apply(getCursor(), target.getCoordinates().replace());
            }

            private J.Annotation applyKotlinTemplate(String annotationText,
                                                      @Nullable String idTypeImport,
                                                      J.Annotation target) {
                KotlinTemplate.Builder builder = KotlinTemplate.builder(annotationText)
                        .imports(EVENT_SOURCED_AF5);
                if (idTypeImport != null) {
                    builder = builder.imports(idTypeImport);
                }
                return builder.build().apply(getCursor(), target.getCoordinates().replace());
            }
        };
    }

    /**
     * Find the source-code form of the value assigned to attribute {@code name} on
     * {@code ann}, e.g. for {@code @Foo(bar = "baz")} and {@code name = "bar"} returns the
     * literal {@code "baz"} (with quotes). Returns {@code null} when the attribute is absent.
     */
    private static @Nullable String findArgumentSource(J.Annotation ann, String name) {
        if (ann.getArguments() == null) {
            return null;
        }
        for (J arg : ann.getArguments()) {
            if (!(arg instanceof J.Assignment)) {
                continue;
            }
            J.Assignment a = (J.Assignment) arg;
            if (a.getVariable() instanceof J.Identifier
                    && name.equals(((J.Identifier) a.getVariable()).getSimpleName())) {
                J value = a.getAssignment();
                if (value instanceof J.Literal) {
                    String src = ((J.Literal) value).getValueSource();
                    if (src != null) {
                        return src;
                    }
                }
                return value.toString().trim();
            }
        }
        return null;
    }

    /**
     * Prepend a {@code //}-style line comment to {@code ann}, placed on its own line above
     * the annotation at the same indent level.
     * <p>
     * The annotation's own {@link Space#getWhitespace() prefix whitespace} is typically
     * empty — the leading newlines and indent that visually appear above the annotation
     * actually live on the parent (e.g. the enclosing {@code J.ClassDeclaration}'s prefix).
     * We therefore do not modify the prefix whitespace; instead we attach the comment with
     * a suffix of {@code "\n" + indent}, which terminates the comment line and re-establishes
     * the indent for the {@code @} symbol that follows.
     */
    private static J.Annotation prependLineComment(J.Annotation ann, String text) {
        Space prefix = ann.getPrefix();
        String leading = prefix.getWhitespace();
        // Indent is whatever sits after the last newline in the available whitespace context.
        // When the annotation's own whitespace is empty we have no signal here; falling back
        // to "" yields a column-0 anchor for the @ symbol, which is correct for top-level
        // class annotations (the only place AF4 `@Aggregate` is allowed).
        String indent = leading.contains("\n")
                ? leading.substring(leading.lastIndexOf('\n') + 1)
                : "";
        String suffix = "\n" + indent;
        TextComment todo = new TextComment(false, text, suffix, Markers.EMPTY);
        return ann.withPrefix(prefix.withComments(ListUtils.concat(prefix.getComments(), todo)));
    }

    private static boolean hasAggregateIdentifier(J.VariableDeclarations vd) {
        for (J.Annotation ann : vd.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), AGGREGATE_IDENTIFIER_AF4)
                    || TypeUtils.isOfClassType(ann.getType(), AGGREGATE_IDENTIFIER_AF5)) {
                return true;
            }
            if (ann.getAnnotationType() instanceof J.Identifier
                    && "AggregateIdentifier".equals(
                            ((J.Identifier) ann.getAnnotationType()).getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEventSourced(J.Annotation ann) {
        return TypeUtils.isOfClassType(ann.getType(), EVENT_SOURCED_AF5)
                || (ann.getAnnotationType() instanceof J.Identifier
                        && "EventSourced".equals(
                                ((J.Identifier) ann.getAnnotationType()).getSimpleName()));
    }

    private static boolean hasTagKey(J.Annotation ann) {
        if (ann.getArguments() == null) {
            return false;
        }
        for (J arg : ann.getArguments()) {
            if (arg instanceof J.Assignment) {
                J variable = ((J.Assignment) arg).getVariable();
                if (variable instanceof J.Identifier
                        && "tagKey".equals(((J.Identifier) variable).getSimpleName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
