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
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Map;

/**
 * Rewrites the AF4 aggregate-fixture constructor expression
 * {@code new AggregateTestFixture<X>(X.class)} (or its diamond form) to the AF5
 * fluent factory:
 * <pre>{@code
 * AxonTestFixture.with(
 *         EventSourcingConfigurer.create()
 *                                .registerEntity(
 *                                        EventSourcedEntityModule.autodetected(
 *                                                <IdType>.class,
 *                                                X.class)))
 * }</pre>
 *
 * <p>The aggregate type {@code X} is read from the constructor's class literal
 * argument. The {@code <IdType>} is looked up in the cross-recipe map populated
 * by {@link AddEventTagAnnotation} (under {@link AddEventTagAnnotation#SHARED_ID_TYPES_KEY})
 * while {@code @AggregateIdentifier} is still on the source. When the id type is
 * unavailable (no annotated field, or an unresolvable type), the recipe falls
 * back to {@code Object.class} with a {@code TODO #LLM} comment so the developer
 * notices and supplies the correct type manually — same shape as
 * {@link ConfigureEventSourcedAnnotation}.
 *
 * <p>Only the AF4 {@code AggregateTestFixture} FQN is matched, so
 * {@code SagaTestFixture} setups are left for manual migration. The recipe
 * targets the {@code new ...} expression itself, so callers in field
 * initializers or {@code @BeforeEach} bodies are both handled.
 *
 * <p><b>When to run:</b> before {@code ChangeType
 * AggregateTestFixture → AxonTestFixture}. Once the type is renamed, the
 * AF4-specific match disappears and the recipe is a no-op.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class MigrateAggregateTestFixtureSetup extends Recipe {

    private static final String AF4_AGGREGATE_TEST_FIXTURE =
            "org.axonframework.test.aggregate.AggregateTestFixture";
    private static final String AF5_AXON_TEST_FIXTURE =
            "org.axonframework.test.fixture.AxonTestFixture";
    private static final String AF5_EVENT_SOURCING_CONFIGURER =
            "org.axonframework.eventsourcing.configuration.EventSourcingConfigurer";
    private static final String AF5_EVENT_SOURCED_ENTITY_MODULE =
            "org.axonframework.eventsourcing.configuration.EventSourcedEntityModule";

    private static final String TODO_ID_TYPE =
            "Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */";

    @Override
    public String getDisplayName() {
        return "Migrate AggregateTestFixture setup to AxonTestFixture.with(EventSourcingConfigurer...)";
    }

    @Override
    public String getDescription() {
        return "Rewrites `new AggregateTestFixture<X>(X.class)` (the AF4 aggregate-fixture constructor) "
                + "to AF5's `AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity("
                + "EventSourcedEntityModule.autodetected(<IdType>.class, X.class)))`. The aggregate "
                + "type X is read from the class-literal constructor argument; the id type is looked "
                + "up via the cross-recipe map populated by AddEventTagAnnotation (while "
                + "@AggregateIdentifier is still on the source). Only matches the AF4 "
                + "AggregateTestFixture FQN — SagaTestFixture setups are left for manual migration.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitNewClass(J.NewClass nc, ExecutionContext ctx) {
                J.NewClass newClass = (J.NewClass) super.visitNewClass(nc, ctx);

                if (!TypeUtils.isOfClassType(newClass.getType(), AF4_AGGREGATE_TEST_FIXTURE)) {
                    return newClass;
                }
                if (newClass.getArguments() == null
                        || newClass.getArguments().isEmpty()
                        || newClass.getArguments().get(0) instanceof J.Empty) {
                    return newClass;
                }
                // Single-arg constructor only — multi-arg AF4 forms (e.g. with a
                // ParameterResolverFactory) are uncommon and need manual review.
                if (newClass.getArguments().size() != 1) {
                    return newClass;
                }

                JavaType.FullyQualified aggregateType =
                        extractClassLiteralType(newClass.getArguments().get(0));
                if (aggregateType == null) {
                    return newClass;
                }

                String aggregateFqn = aggregateType.getFullyQualifiedName();
                String aggregateSimple = aggregateType.getClassName();

                @SuppressWarnings("unchecked")
                Map<String, String> shared = (Map<String, String>) ctx
                        .getMessage(AddEventTagAnnotation.SHARED_ID_TYPES_KEY);
                String idTypeFqn = shared != null ? shared.get(aggregateFqn) : null;

                String idTypeExpr;
                String idTypeImport;
                if (idTypeFqn == null) {
                    idTypeExpr = TODO_ID_TYPE;
                    idTypeImport = null;
                } else {
                    String simpleName = idTypeFqn.substring(idTypeFqn.lastIndexOf('.') + 1);
                    idTypeExpr = simpleName + ".class";
                    idTypeImport = idTypeFqn.startsWith("java.lang.") ? null : idTypeFqn;
                }

                String template = "AxonTestFixture.with("
                        + "EventSourcingConfigurer.create()"
                        + ".registerEntity(EventSourcedEntityModule.autodetected("
                        + idTypeExpr + ", " + aggregateSimple + ".class)))";

                JavaTemplate.Builder builder = JavaTemplate.builder(template)
                        .imports(AF5_AXON_TEST_FIXTURE,
                                 AF5_EVENT_SOURCING_CONFIGURER,
                                 AF5_EVENT_SOURCED_ENTITY_MODULE)
                        .javaParser(JavaParser.fromJavaVersion()
                                              .classpath(JavaParser.runtimeClasspath()));
                if (idTypeImport != null) {
                    builder = builder.imports(idTypeImport);
                }

                J replaced = builder.build().apply(getCursor(), newClass.getCoordinates().replace());

                // Force-add the imports rather than relying on the
                // "only-if-referenced" heuristic: in test scope the AF5 jars
                // are not on the parser classpath, so the JavaTemplate-built
                // tree carries unresolved types and the heuristic skips them.
                maybeAddImport(AF5_AXON_TEST_FIXTURE, null, false);
                maybeAddImport(AF5_EVENT_SOURCING_CONFIGURER, null, false);
                maybeAddImport(AF5_EVENT_SOURCED_ENTITY_MODULE, null, false);
                if (idTypeImport != null) {
                    maybeAddImport(idTypeImport, null, false);
                }
                return replaced;
            }

            /**
             * Returns the FQN of {@code X} in {@code X.class}, or {@code null}
             * when the argument is not a direct class literal whose target type
             * resolves to a fully-qualified type.
             */
            private JavaType.FullyQualified extractClassLiteralType(Expression arg) {
                if (!(arg instanceof J.FieldAccess)) {
                    return null;
                }
                J.FieldAccess fa = (J.FieldAccess) arg;
                if (!"class".equals(fa.getSimpleName())) {
                    return null;
                }
                return TypeUtils.asFullyQualified(fa.getTarget().getType());
            }
        };
    }
}
