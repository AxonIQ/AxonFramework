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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * In Spring Boot applications that use {@code MessageOriginProvider}, creates (or updates) a
 * {@code CorrelationDataProviderConfiguration} {@code @Configuration} class that registers the
 * provider as a Spring bean with Axon Framework 4-compatible metadata key names.
 * <p>
 * Spring Boot applications typically rely on bean discovery to register
 * {@link org.axonframework.messaging.core.correlation.CorrelationDataProvider} implementations.
 * After the AF4 → AF5 migration the default constructor of {@code MessageOriginProvider} writes
 * metadata under new key names: AF4's {@code "traceId"} (originating-message id, propagated)
 * became AF5's default {@code "correlationId"}, and AF4's {@code "correlationId"} (current-message
 * id, the direct cause) became AF5's default {@code "causationId"}. This recipe wires the provider
 * as a Spring bean with explicit {@code correlationKey="traceId"} /
 * {@code causationKey="correlationId"}, preserving the AF4-compatible key names in message
 * metadata so existing consumers are not broken.
 * <p>
 * Behavior:
 * <ul>
 *   <li>If no {@code CorrelationDataProviderConfiguration} class exists in the project, and a
 *       {@code @SpringBootApplication} class is present, a new configuration class is generated
 *       in the application's root package.</li>
 *   <li>If a {@code CorrelationDataProviderConfiguration} class already exists but lacks the
 *       {@code messageOriginProvider} {@code @Bean} method, the method is added to it.</li>
 *   <li>If the {@code @Bean} method already exists, the class is left unchanged (idempotent).</li>
 *   <li>Projects with no {@code new MessageOriginProvider()} usage are not modified.</li>
 * </ul>
 * <p>
 * Intended to run as part of the {@code Axon4ToAxon5SpringBootExtension} recipe so that it has
 * access to the Spring Boot classpath. Must run after {@code Axon4ToAxon5Messaging} so that
 * package renames have already produced the AF5 FQNs in the imports.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class AddMessageOriginProviderSpringBeanConfiguration
        extends ScanningRecipe<AddMessageOriginProviderSpringBeanConfiguration.Accumulator> {

    private static final String MOP_AF4 =
            "org.axonframework.messaging.correlation.MessageOriginProvider";
    private static final String MOP_AF5 =
            "org.axonframework.messaging.core.correlation.MessageOriginProvider";
    private static final String CDP_AF5 =
            "org.axonframework.messaging.core.correlation.CorrelationDataProvider";
    private static final String SPRING_BOOT_APP_FQN =
            "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String BEAN_FQN =
            "org.springframework.context.annotation.Bean";
    private static final String CONFIGURATION_FQN =
            "org.springframework.context.annotation.Configuration";
    private static final String CONFIG_CLASS_NAME = "CorrelationDataProviderConfiguration";
    private static final String BEAN_METHOD_NAME = "messageOriginProvider";

    public static class Accumulator {

        boolean messageOriginProviderUsed;
        boolean configClassExists;
        /** True when the {@code messageOriginProvider} @Bean already returns an explicit-args constructor. */
        boolean configClassHasCustomArgs;
        String rootPackage;
    }

    @Override
    public String getDisplayName() {
        return "Create CorrelationDataProviderConfiguration Spring bean for MessageOriginProvider";
    }

    @Override
    public String getDescription() {
        return "In Spring Boot applications using MessageOriginProvider, creates (or updates) a "
                + "`CorrelationDataProviderConfiguration` @Configuration class with a @Bean method "
                + "returning `new MessageOriginProvider(\"traceId\", \"correlationId\")` to register "
                + "the provider as a Spring bean with Axon Framework 4-compatible metadata key names. "
                + "AF5 renamed the keys: AF4's `traceId` (propagated originating-message id) became "
                + "AF5's `correlationKey`, and AF4's `correlationId` (direct-cause id) became "
                + "AF5's `causationKey`. This bean preserves the old key names in output metadata.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, ExecutionContext ctx) {
                if (isMessageOriginProviderNoArgs(nc)) {
                    acc.messageOriginProviderUsed = true;
                }
                return super.visitNewClass(nc, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd,
                                                             ExecutionContext ctx) {
                if (CONFIG_CLASS_NAME.equals(cd.getSimpleName())) {
                    acc.configClassExists = true;
                }
                if (acc.rootPackage == null && isSpringBootApplication(cd)) {
                    if (cd.getType() != null) {
                        acc.rootPackage = cd.getType().getPackageName();
                    }
                }
                return super.visitClassDeclaration(cd, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                               ExecutionContext ctx) {
                // Detect whether the existing @Bean method already uses explicit constructor args.
                // If it does, the developer chose their own keys and we must not override them.
                J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosingClass != null
                        && CONFIG_CLASS_NAME.equals(enclosingClass.getSimpleName())
                        && BEAN_METHOD_NAME.equals(method.getName().getSimpleName())
                        && method.getBody() != null) {
                    for (Statement stmt : method.getBody().getStatements()) {
                        if (stmt instanceof J.Return ret
                                && ret.getExpression() instanceof J.NewClass nc
                                && isMessageOriginProviderClass(nc)
                                && !isNoArgs(nc)) {
                            acc.configClassHasCustomArgs = true;
                        }
                    }
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            private boolean isMessageOriginProviderClass(J.NewClass nc) {
                if (TypeUtils.isOfClassType(nc.getType(), MOP_AF4)
                        || TypeUtils.isOfClassType(nc.getType(), MOP_AF5)) {
                    return true;
                }
                if (nc.getClazz() instanceof J.Identifier) {
                    return "MessageOriginProvider".equals(
                            ((J.Identifier) nc.getClazz()).getSimpleName());
                }
                return false;
            }

            private boolean isNoArgs(J.NewClass nc) {
                List<Expression> args = nc.getArguments();
                return args == null
                        || args.isEmpty()
                        || (args.size() == 1 && args.get(0) instanceof J.Empty);
            }

            private boolean isMessageOriginProviderNoArgs(J.NewClass nc) {
                return isNoArgs(nc) && isMessageOriginProviderClass(nc);
            }

            private boolean isSpringBootApplication(J.ClassDeclaration cd) {
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), SPRING_BOOT_APP_FQN)) {
                        return true;
                    }
                    if (ann.getAnnotationType() instanceof J.Identifier) {
                        String simpleName = ((J.Identifier) ann.getAnnotationType()).getSimpleName();
                        if ("SpringBootApplication".equals(simpleName)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (!acc.messageOriginProviderUsed || !acc.configClassExists
                || acc.configClassHasCustomArgs) {
            // configClassHasCustomArgs: the @Bean already returns an explicit-args constructor —
            // the developer intentionally chose their own key names, so we must not override them.
            return TreeVisitor.noop();
        }
        return new JavaIsoVisitor<>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd,
                                                             ExecutionContext ctx) {
                J.ClassDeclaration result = super.visitClassDeclaration(cd, ctx);
                if (!CONFIG_CLASS_NAME.equals(cd.getSimpleName())) {
                    return result;
                }
                if (hasBeanMethod(result)) {
                    // Method already exists (with no-args constructor).
                    // MigrateMessageOriginProviderDefaultKeys handles updating the args.
                    return result;
                }
                result = JavaTemplate.builder(
                        "@Bean\n"
                                + "public CorrelationDataProvider messageOriginProvider() {\n"
                                + "    return new MessageOriginProvider(\"traceId\", \"correlationId\");\n"
                                + "}\n")
                        .imports(CDP_AF5, MOP_AF5, BEAN_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), result.getBody().getCoordinates().lastStatement());
                maybeAddImport(CDP_AF5, null, false);
                maybeAddImport(MOP_AF5, null, false);
                maybeAddImport(BEAN_FQN, null, false);
                return result;
            }

            private boolean hasBeanMethod(J.ClassDeclaration cd) {
                for (Statement stmt : cd.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration method) {
                        if (BEAN_METHOD_NAME.equals(method.getName().getSimpleName())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc,
                                                      Collection<SourceFile> generatedInThisCycle,
                                                      ExecutionContext ctx) {
        if (!acc.messageOriginProviderUsed || acc.configClassExists || acc.rootPackage == null) {
            return Collections.emptyList();
        }
        String pkg = acc.rootPackage;
        String source = buildConfigClassSource(pkg);
        Path sourcePath = Paths.get(
                "src/main/java/" + pkg.replace('.', '/') + "/" + CONFIG_CLASS_NAME + ".java");
        List<SourceFile> result = new ArrayList<>();
        JavaParser.fromJavaVersion()
                .build()
                .parse(source)
                .forEach(sf -> result.add(sf.withSourcePath(sourcePath)));
        return result;
    }

    private static String buildConfigClassSource(String pkg) {
        return "package " + pkg + ";\n\n"
                + "import " + CDP_AF5 + ";\n"
                + "import " + MOP_AF5 + ";\n"
                + "import " + BEAN_FQN + ";\n"
                + "import " + CONFIGURATION_FQN + ";\n\n"
                + "@Configuration\n"
                + "public class " + CONFIG_CLASS_NAME + " {\n\n"
                + "    @Bean\n"
                + "    public CorrelationDataProvider messageOriginProvider() {\n"
                + "        return new MessageOriginProvider(\"traceId\", \"correlationId\");\n"
                + "    }\n"
                + "}\n";
    }
}
