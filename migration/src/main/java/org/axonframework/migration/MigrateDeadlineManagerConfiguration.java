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
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Migrates the Axon Framework 4 configuration code around a deadline manager that the Axoniq Framework legacy module
 * does not keep.
 * <p>
 * Axon Framework 4's {@code ConfigurationScopeAwareProvider} collected the components a fired deadline is delivered
 * to from the configuration. The legacy module does not port it: it registers its own {@code ScopeAwareProvider} as a
 * component of the configuration, with which the Saga managers register themselves. The recipe therefore replaces
 * {@code new ConfigurationScopeAwareProvider(configuration)} with
 * {@code configuration.getComponent(ScopeAwareProvider.class)}, which compiles against both the Axon Framework 4 and
 * the Axon Framework 5 {@code Configuration}. For the same reason, it replaces {@code configuration.deadlineManager()}
 * with {@code configuration.getComponent(DeadlineManager.class)}.
 * <p>
 * Axon Framework 4 registered a {@code SimpleDeadlineManager} by default, and Axoniq Framework 5 registers no
 * {@code DeadlineManager}, except for the JobRunr and db-scheduler deadline managers of its Spring Boot
 * auto-configuration. Where the replacement is a decision of the application, the recipe leaves a
 * {@code // TODO(axon4to5):} marker on the statement:
 * <ul>
 *     <li>{@code configuration.deadlineManager()}, as the component it now looks up has to be registered;</li>
 *     <li>{@code Configurer#configureDeadlineManager(..)}, which Axon Framework 5 configurers do not have;</li>
 *     <li>{@code registerDispatchInterceptor(..)} and {@code registerHandlerInterceptor(..)} called on a
 *     {@code DeadlineManager}, as the interface no longer declares them.</li>
 * </ul>
 * The recipe is idempotent: a statement that already carries a marker is not marked again.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateDeadlineManagerConfiguration extends Recipe {

    private static final String CONFIGURATION_SCOPE_AWARE_PROVIDER_FQN =
            "org.axonframework.config.ConfigurationScopeAwareProvider";
    private static final String SCOPE_AWARE_PROVIDER_FQN = "org.axonframework.messaging.ScopeAwareProvider";
    private static final String CONFIGURATION_FQN = "org.axonframework.config.Configuration";
    private static final String CONFIG_PACKAGE_PREFIX = "org.axonframework.config.";
    private static final String DEADLINE_MANAGER_FQN = "org.axonframework.deadline.DeadlineManager";
    private static final String DEADLINE_MANAGER_METHOD = "deadlineManager";
    private static final String CONFIGURE_DEADLINE_MANAGER_METHOD = "configureDeadlineManager";
    private static final Set<String> INTERCEPTOR_METHODS =
            Set.of("registerDispatchInterceptor", "registerHandlerInterceptor");

    private static final String COMPONENT_MARKER = "TODO(axon4to5): register a DeadlineManager component";
    private static final String COMPONENT_TODO = " " + COMPONENT_MARKER
            + " or Spring bean, unless the application already does. Axon Framework 4 registered a"
            + " SimpleDeadlineManager by default; Axoniq Framework 5 registers none, except the JobRunr and"
            + " db-scheduler deadline managers of its Spring Boot auto-configuration.";
    private static final String CONFIGURER_MARKER = "TODO(axon4to5): register the DeadlineManager as a component";
    private static final String CONFIGURER_TODO = " " + CONFIGURER_MARKER
            + " instead, with componentRegistry(registry -> registry.registerComponent(DeadlineManager.class,"
            + " configuration -> ...)) on the Axon Framework 5 configurer, or as a Spring bean. Axon Framework 5"
            + " configurers have no configureDeadlineManager(..); Axon Framework 4 also registered a"
            + " SimpleDeadlineManager by default, Axoniq Framework 5 registers none.";
    private static final String INTERCEPTOR_MARKER =
            "TODO(axon4to5): register this interceptor on the concrete deadline manager";
    private static final String INTERCEPTOR_TODO = " " + INTERCEPTOR_MARKER
            + " instance, such as the SimpleDeadlineManager you build, before registering it as a component. The"
            + " DeadlineManager interface no longer declares registerDispatchInterceptor(..) and"
            + " registerHandlerInterceptor(..); AbstractDeadlineManager does, with Axon Framework 5 interceptor types.";

    @Override
    public String getDisplayName() {
        return "Migrate legacy deadline manager configuration";
    }

    @Override
    public String getDescription() {
        return "Replaces `new ConfigurationScopeAwareProvider(configuration)`, which the Axoniq Framework legacy module "
                + "does not port, with `configuration.getComponent(ScopeAwareProvider.class)`, and "
                + "`configuration.deadlineManager()` with `configuration.getComponent(DeadlineManager.class)`. Leaves "
                + "a `// TODO(axon4to5):` marker on `configureDeadlineManager(..)`, on `deadlineManager()` lookups, "
                + "and on interceptor registration on a `DeadlineManager`, which Axon Framework 5 does not keep. "
                + "Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                // Markers are decided on the Axon Framework 4 statements, before deadlineManager() is replaced.
                Map<UUID, Map<String, String>> markers = new HashMap<>();
                for (Statement statement : block.getStatements()) {
                    Map<String, String> statementMarkers = markersFor(statement);
                    if (!statementMarkers.isEmpty()) {
                        markers.put(statement.getId(), statementMarkers);
                    }
                }
                J.Block visited = (J.Block) super.visitBlock(block, ctx);
                if (markers.isEmpty()) {
                    return visited;
                }
                return visited.withStatements(ListUtils.map(visited.getStatements(), statement -> {
                    Statement marked = statement;
                    for (Map.Entry<String, String> marker : markers.getOrDefault(statement.getId(), Map.of())
                                                                   .entrySet()) {
                        marked = mark(marked, marker.getKey(), marker.getValue());
                    }
                    return marked;
                }));
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J visited = super.visitMethodInvocation(method, ctx);
                if (!(visited instanceof J.MethodInvocation invocation)
                        || !isConfigurationDeadlineManagerLookup(invocation)) {
                    return visited;
                }
                maybeAddImport(DEADLINE_MANAGER_FQN, null, false);
                return JavaTemplate.builder("#{any(org.axonframework.config.Configuration)}"
                                                    + ".getComponent(DeadlineManager.class)")
                                   .imports(DEADLINE_MANAGER_FQN)
                                   .javaParser(JavaParser.fromJavaVersion()
                                                         .classpath(JavaParser.runtimeClasspath()))
                                   .build()
                                   .apply(new Cursor(getCursor().getParentOrThrow(), invocation),
                                          invocation.getCoordinates().replace(),
                                          invocation.getSelect());
            }

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

    /**
     * Collects the markers for the given statement, not looking into nested blocks, which mark their own statements.
     */
    private static Map<String, String> markersFor(Statement statement) {
        Map<String, String> markers = new LinkedHashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block block, Integer p) {
                return block;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if (isConfigurationDeadlineManagerLookup(invocation)) {
                    markers.put(COMPONENT_MARKER, COMPONENT_TODO);
                } else if (isConfigureDeadlineManager(invocation)) {
                    markers.put(CONFIGURER_MARKER, CONFIGURER_TODO);
                } else if (isInterceptorRegistrationOnDeadlineManager(invocation)) {
                    markers.put(INTERCEPTOR_MARKER, INTERCEPTOR_TODO);
                }
                return super.visitMethodInvocation(invocation, p);
            }
        }.visit(statement, 0);
        return markers;
    }

    /**
     * Matches {@code configuration.deadlineManager()} on an Axon Framework 4 {@code Configuration}. The name alone is
     * too common, a Spring {@code @Bean} method for one, so the call has to resolve.
     */
    private static boolean isConfigurationDeadlineManagerLookup(J.MethodInvocation invocation) {
        JavaType.Method methodType = invocation.getMethodType();
        return DEADLINE_MANAGER_METHOD.equals(invocation.getSimpleName())
                && invocation.getSelect() != null
                && methodType != null
                && methodType.getParameterTypes().isEmpty()
                && TypeUtils.isAssignableTo(CONFIGURATION_FQN, methodType.getDeclaringType());
    }

    private static boolean isConfigureDeadlineManager(J.MethodInvocation invocation) {
        if (!CONFIGURE_DEADLINE_MANAGER_METHOD.equals(invocation.getSimpleName())) {
            return false;
        }
        JavaType.Method methodType = invocation.getMethodType();
        return methodType == null
                || methodType.getDeclaringType().getFullyQualifiedName().startsWith(CONFIG_PACKAGE_PREFIX);
    }

    private static boolean isInterceptorRegistrationOnDeadlineManager(J.MethodInvocation invocation) {
        Expression select = invocation.getSelect();
        return INTERCEPTOR_METHODS.contains(invocation.getSimpleName())
                && select != null
                && TypeUtils.isOfClassType(select.getType(), DEADLINE_MANAGER_FQN);
    }

    /**
     * Prepends the TODO line comment to the element's prefix, keeping the element on its original indent below it.
     */
    private static <T extends J> T mark(T element, String marker, String todo) {
        Space prefix = element.getPrefix();
        for (Comment comment : prefix.getComments()) {
            if (comment instanceof TextComment text && text.getText().contains(marker)) {
                return element;
            }
        }
        String leading = prefix.getComments().isEmpty()
                ? prefix.getWhitespace()
                : prefix.getComments().getLast().getSuffix();
        String indent = leading.contains("\n") ? leading.substring(leading.lastIndexOf('\n') + 1) : "";
        TextComment comment = new TextComment(false, todo, "\n" + indent, Markers.EMPTY);
        return element.withPrefix(prefix.withComments(ListUtils.concat(prefix.getComments(), comment)));
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
