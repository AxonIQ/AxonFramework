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
import org.openrewrite.java.tree.TypeUtils;

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

    private static final String TODO_FALLBACK =
            "Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */";

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

                String idTypeExpr;
                String idTypeImport;
                if (idTypeFqn == null) {
                    idTypeExpr = TODO_FALLBACK;
                    idTypeImport = null;
                } else {
                    String simpleName = idTypeFqn.substring(idTypeFqn.lastIndexOf('.') + 1);
                    idTypeExpr = simpleName + ".class";
                    // java.lang types are auto-imported.
                    idTypeImport = idTypeFqn.startsWith("java.lang.") ? null : idTypeFqn;
                }

                String newAnnotationText = "@EventSourced(tagKey = \"" + tagKey
                        + "\", idType = " + idTypeExpr + ")";

                JavaTemplate.Builder builder = JavaTemplate.builder(newAnnotationText)
                        .imports(EVENT_SOURCED_AF5)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
                if (idTypeImport != null) {
                    builder = builder.imports(idTypeImport);
                }

                J.Annotation result = builder.build()
                        .apply(getCursor(), ann.getCoordinates().replace());

                if (idTypeImport != null) {
                    maybeAddImport(idTypeImport);
                }
                return result;
            }
        };
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
