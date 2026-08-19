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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Strips generic type arguments from references to a specific fully-qualified type.
 * <p>
 * Several AF5 message types ({@code Message}, {@code EventMessage}, {@code CommandMessage},
 * {@code QueryMessage}, {@code QueryResponseMessage}, {@code ResultMessage}) dropped the
 * payload-type parameter that AF4 carried. The {@code ChangeType} recipe relocates the type
 * but keeps the {@code <T>} usage in source, leading to compile errors like
 * "Type ... does not have type parameters". This recipe rewrites
 * {@code EventMessage<Foo>} → {@code EventMessage} (and the matching {@code <?>}/{@code <? extends X>}
 * forms) so source compiles against the AF5 raw type.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class RemoveTypeArguments extends Recipe {

    @Option(displayName = "Fully-qualified type name",
            description = "The type whose type arguments should be stripped wherever it appears parameterized.",
            example = "org.axonframework.messaging.eventhandling.EventMessage")
    private final String fullyQualifiedTypeName;

    @JsonCreator
    public RemoveTypeArguments(@JsonProperty("fullyQualifiedTypeName") String fullyQualifiedTypeName) {
        this.fullyQualifiedTypeName = fullyQualifiedTypeName;
    }

    public String getFullyQualifiedTypeName() {
        return fullyQualifiedTypeName;
    }

    @Override
    public String getDisplayName() {
        return "Remove type arguments from a specific type";
    }

    @Override
    public String getDescription() {
        return "Removes the generic type arguments (`<...>`) from any usage of the configured "
                + "fully-qualified type. Preserves the underlying type reference and its surrounding "
                + "context (variables, parameters, return types, generic bounds, etc.).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ParameterizedType visitParameterizedType(J.ParameterizedType type, ExecutionContext ctx) {
                J.ParameterizedType pt = super.visitParameterizedType(type, ctx);
                if (pt.getTypeParameters() == null || pt.getTypeParameters().isEmpty()) {
                    return pt;
                }
                if (TypeUtils.isOfClassType(pt.getClazz().getType(), fullyQualifiedTypeName)) {
                    return pt.withTypeParameters(null);
                }
                return pt;
            }
        };
    }
}
