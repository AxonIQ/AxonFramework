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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Migrates the Axon Framework 4 configuration code around a deadline manager that the Axoniq Framework legacy module
 * does not keep.
 * <p>
 * Axon Framework 4's {@code ConfigurationScopeAwareProvider} collected the components a fired deadline is delivered
 * to from the configuration. The legacy module does not port it: it registers its own {@code ScopeAwareProvider} as a
 * component of the configuration, with which the Saga managers register themselves. The recipe therefore replaces
 * {@code new ConfigurationScopeAwareProvider(configuration)} with
 * {@code configuration.getComponent(ScopeAwareProvider.class)}, which compiles against both the Axon Framework 4 and
 * the Axon Framework 5 {@code Configuration}.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateDeadlineManagerConfiguration extends Recipe {

    private static final String CONFIGURATION_SCOPE_AWARE_PROVIDER_FQN =
            "org.axonframework.config.ConfigurationScopeAwareProvider";
    private static final String SCOPE_AWARE_PROVIDER_FQN = "org.axonframework.messaging.ScopeAwareProvider";

    @Override
    public String getDisplayName() {
        return "Migrate legacy deadline manager configuration";
    }

    @Override
    public String getDescription() {
        return "Replaces `new ConfigurationScopeAwareProvider(configuration)`, which the Axoniq Framework legacy module "
                + "does not port, with `configuration.getComponent(ScopeAwareProvider.class)`.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J visited = super.visitNewClass(newClass, ctx);
                if (!(visited instanceof J.NewClass created)
                        || created.getBody() != null
                        || created.getArguments().size() != 1
                        || !isConfigurationScopeAwareProvider(created)) {
                    return visited;
                }
                maybeRemoveImport(CONFIGURATION_SCOPE_AWARE_PROVIDER_FQN);
                maybeAddImport(SCOPE_AWARE_PROVIDER_FQN, null, false);
                return JavaTemplate.builder("#{any(org.axonframework.config.Configuration)}"
                                                    + ".getComponent(ScopeAwareProvider.class)")
                                   .imports(SCOPE_AWARE_PROVIDER_FQN)
                                   .javaParser(JavaParser.fromJavaVersion()
                                                         .classpath(JavaParser.runtimeClasspath()))
                                   .build()
                                   .apply(new Cursor(getCursor().getParentOrThrow(), created),
                                          created.getCoordinates().replace(),
                                          created.getArguments().getFirst());
            }
        };
    }

    private static boolean isConfigurationScopeAwareProvider(J.NewClass newClass) {
        if (newClass.getClazz() == null) {
            return false;
        }
        String type = resolvedName(newClass.getClazz().getType());
        if (type != null) {
            return CONFIGURATION_SCOPE_AWARE_PROVIDER_FQN.equals(type);
        }
        return newClass.getClazz() instanceof J.Identifier identifier
                && "ConfigurationScopeAwareProvider".equals(identifier.getSimpleName());
    }

    /**
     * Returns the fully qualified name of a resolved type, or {@code null} when the type could not be resolved. The
     * callers fall back to the simple name only in that case, so a user type that happens to share a simple name with
     * an Axon type is not mistaken for it.
     */
    private static @Nullable String resolvedName(@Nullable JavaType javaType) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(javaType);
        return type == null || type instanceof JavaType.Unknown ? null : type.getFullyQualifiedName();
    }
}
