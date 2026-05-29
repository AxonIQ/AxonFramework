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
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.tree.K;

/**
 * Adds {@code @AfterEach void tearDown() { <fixture>.stop(); }} to test
 * classes that declare a field of type {@code AxonTestFixture} but have
 * no existing {@code @AfterEach} (and no method already named
 * {@code tearDown}). Pairs with
 * {@link MigrateAggregateTestFixtureSetup} which produces the field; the
 * tear-down step was previously left for manual migration.
 *
 * <p>Why automate it: every {@code AxonTestFixture} owns lifecycle
 * resources (configurer + entity registrations + lifecycle-managed
 * components). Without {@code stop()} between tests the resources leak
 * across test methods, which surfaces as flaky / cross-test interference
 * under faster reruns. Generating the {@code @AfterEach} consistently is
 * safer than relying on each test author to remember.
 *
 * <p>The recipe is conservative:
 * <ul>
 *   <li>Requires at least one method annotated {@code @BeforeEach} on the
 *       same class. A class that needs {@code @AfterEach} cleanup almost
 *       always already has {@code @BeforeEach} setup; demanding the
 *       counterpart filters out non-JUnit-5 classes (and the
 *       recipe-test toy fixtures that are not real test classes) without
 *       requiring resolved annotation types.</li>
 *   <li>Skips when the class already has any method annotated with
 *       {@code @AfterEach} — the user's cleanup might already invoke
 *       {@code stop()} or might be intentionally orthogonal; either way,
 *       silently appending a sibling could clash.</li>
 *   <li>Skips when the class already has a method named {@code tearDown}
 *       (regardless of annotation) — avoids generating a duplicate
 *       method that would not compile.</li>
 *   <li>Java sources only. Kotlin sources fall through to a no-op so
 *       call sites compile; Kotlin support is left for a sibling recipe
 *       using {@code KotlinTemplate}, mirroring
 *       {@link MigrateAggregateTestFixtureSetup}.</li>
 * </ul>
 *
 * <p><b>When to run:</b> after {@code ChangeType
 * AggregateTestFixture → AxonTestFixture}, so the field type already
 * carries the AF5 FQN that this recipe matches. Idempotent on re-runs:
 * a second pass detects the just-inserted {@code @AfterEach} and skips.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class AddAxonTestFixtureTearDown extends Recipe {

    private static final String AF5_AXON_TEST_FIXTURE =
            "org.axonframework.test.fixture.AxonTestFixture";
    private static final String AF5_AXON_TEST_FIXTURE_SIMPLE = "AxonTestFixture";
    private static final String JUNIT_AFTER_EACH =
            "org.junit.jupiter.api.AfterEach";
    private static final String JUNIT_AFTER_EACH_SIMPLE = "AfterEach";
    private static final String JUNIT_BEFORE_EACH =
            "org.junit.jupiter.api.BeforeEach";
    private static final String JUNIT_BEFORE_EACH_SIMPLE = "BeforeEach";
    private static final String TEAR_DOWN_METHOD_NAME = "tearDown";

    @Override
    public String getDisplayName() {
        return "Add @AfterEach tearDown() that stops the AxonTestFixture";
    }

    @Override
    public String getDescription() {
        return "Adds an `@AfterEach tearDown()` method calling `stop()` on the "
                + "`AxonTestFixture` field, when the test class has such a "
                + "field but no existing `@AfterEach` method (and no method "
                + "named `tearDown`). Pairs with "
                + "`MigrateAggregateTestFixtureSetup` which produces the "
                + "field; the tear-down step was previously left for manual "
                + "migration. Java sources only.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(cd, ctx);

                // Kotlin needs KotlinTemplate; out of scope for this recipe.
                if (getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit) {
                    return c;
                }
                if (c.getBody() == null) {
                    return c;
                }

                String fieldName = findAxonTestFixtureFieldName(c);
                if (fieldName == null) {
                    return c;
                }
                if (!hasJUnit5BeforeEach(c)) {
                    return c;
                }
                if (hasJUnit5AfterEach(c) || hasMethodNamed(c, TEAR_DOWN_METHOD_NAME)) {
                    return c;
                }

                JavaTemplate template = JavaTemplate.builder(
                                "@AfterEach\nvoid " + TEAR_DOWN_METHOD_NAME + "() {\n"
                                        + "    " + fieldName + ".stop();\n"
                                        + "}\n")
                        .imports(JUNIT_AFTER_EACH)
                        .javaParser(JavaParser.fromJavaVersion()
                                              .classpath(JavaParser.runtimeClasspath()))
                        .build();

                c = template.apply(getCursor(), c.getBody().getCoordinates().lastStatement());
                // Force-add the import: in test scope JUnit Jupiter may not
                // be on the parser classpath, so the only-if-referenced
                // heuristic can drop the unresolved annotation reference.
                maybeAddImport(JUNIT_AFTER_EACH, null, false);
                return c;
            }

            /**
             * Returns the simple name of the first {@code AxonTestFixture}-typed
             * field on the class, or {@code null} when no such field exists.
             *
             * <p>Prefers resolved-type matching against the AF5 FQN, with a
             * shape-based fallback (type expression's simple name equals
             * {@code "AxonTestFixture"}) for the common case where AF5
             * {@code axon-test} is not yet on the parser classpath — same
             * approach as {@link MigrateAggregateTestFixtureSetup}, which
             * matches structural shape rather than fully-resolved types so
             * the recipe still works in pre-AF5 toolchains and in test
             * scopes where the new types are unresolved. Pre-rename
             * {@code AggregateTestFixture} fields are intentionally invisible
             * — this recipe waits for the preceding {@code ChangeType} to
             * rename the type first.
             */
            private String findAxonTestFixtureFieldName(J.ClassDeclaration cd) {
                for (Statement st : cd.getBody().getStatements()) {
                    if (!(st instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations vd = (J.VariableDeclarations) st;
                    if (vd.getTypeExpression() == null || vd.getVariables().isEmpty()) {
                        continue;
                    }
                    if (isAxonTestFixtureType(vd.getTypeExpression())) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            private boolean isAxonTestFixtureType(org.openrewrite.java.tree.TypeTree typeExpr) {
                JavaType.FullyQualified resolved = TypeUtils.asFullyQualified(typeExpr.getType());
                if (resolved != null) {
                    return AF5_AXON_TEST_FIXTURE.equals(resolved.getFullyQualifiedName());
                }
                // Shape fallback: simple-name match. Safe in this recipe's
                // chain because the preceding ChangeType has already renamed
                // any AF4 AggregateTestFixture / SagaTestFixture / interface
                // FixtureConfiguration fields to AxonTestFixture; a project
                // that legitimately ships its own unrelated `AxonTestFixture`
                // class would produce a false positive, accepted as a known
                // edge case.
                if (typeExpr instanceof J.Identifier) {
                    return AF5_AXON_TEST_FIXTURE_SIMPLE
                            .equals(((J.Identifier) typeExpr).getSimpleName());
                }
                if (typeExpr instanceof J.FieldAccess) {
                    return AF5_AXON_TEST_FIXTURE
                            .equals(((J.FieldAccess) typeExpr).toString());
                }
                return false;
            }

            private boolean hasJUnit5AfterEach(J.ClassDeclaration cd) {
                return hasMethodAnnotation(cd, JUNIT_AFTER_EACH, JUNIT_AFTER_EACH_SIMPLE);
            }

            private boolean hasJUnit5BeforeEach(J.ClassDeclaration cd) {
                return hasMethodAnnotation(cd, JUNIT_BEFORE_EACH, JUNIT_BEFORE_EACH_SIMPLE);
            }

            /**
             * Class has a method annotated with the named JUnit 5 annotation
             * (FQN match preferred; falls back to simple-name match for the
             * common case where {@code junit-jupiter-api} is not on the parser
             * classpath, which would otherwise leave annotation types unresolved).
             */
            private boolean hasMethodAnnotation(J.ClassDeclaration cd, String fqn, String simpleName) {
                for (Statement st : cd.getBody().getStatements()) {
                    if (!(st instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration md = (J.MethodDeclaration) st;
                    for (J.Annotation a : md.getLeadingAnnotations()) {
                        if (TypeUtils.isOfClassType(a.getType(), fqn)) {
                            return true;
                        }
                        if (a.getAnnotationType() instanceof J.Identifier
                                && simpleName.equals(((J.Identifier) a.getAnnotationType()).getSimpleName())) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasMethodNamed(J.ClassDeclaration cd, String name) {
                for (Statement st : cd.getBody().getStatements()) {
                    if (!(st instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    if (name.equals(((J.MethodDeclaration) st).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
