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
 * Adds explicit {@code tagKey} and {@code idType} attributes to {@code @EventSourced} annotations
 * that do not yet configure them.
 * <p>
 * The {@code tagKey} is set to the entity's simple class name — matching the framework default
 * (an empty {@code tagKey} resolves to the simple class name at runtime, but making it explicit
 * here ensures it is visible for review and stays stable even if the class is later renamed).
 * <p>
 * The {@code idType} is set to {@code Object.class} as a placeholder. The actual identifier
 * type must be set manually (e.g. {@code String.class}, {@code UUID.class}).
 * This placeholder is intentionally wrong so that developers and LLM post-processors notice
 * it immediately and correct it.
 * <p>
 * <b>When to run:</b> after {@code Axon4ToAxon5SpringExtension} has renamed
 * {@code @Aggregate} → {@code @EventSourced}; the recipe only matches the AF5 FQN.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class ConfigureEventSourcedAnnotation extends Recipe {

    private static final String EVENT_SOURCED_AF5 =
            "org.axonframework.extension.spring.stereotype.EventSourced";

    @Override
    public String getDisplayName() {
        return "Add explicit tagKey and idType to @EventSourced";
    }

    @Override
    public String getDescription() {
        return "Adds explicit tagKey = \"<EntitySimpleName>\" and idType = Object.class to "
                + "@EventSourced annotations that have no tagKey set. The tagKey is derived from "
                + "the class simple name (matching the AF5 default). The idType is set to "
                + "Object.class as a visible placeholder — replace it with the actual identifier "
                + "type (e.g. String.class, UUID.class).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
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
                String newAnnotationText = "@EventSourced(tagKey = \"" + tagKey
                        + "\", idType = Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */)";

                return JavaTemplate.builder(newAnnotationText)
                        .imports(EVENT_SOURCED_AF5)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), ann.getCoordinates().replace());
            }
        };
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
