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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.Set;

/**
 * Leaves a {@code // TODO(axon4to5):} marker on the statements that use the deadline and time support of the Axon
 * Framework 4 aggregate test fixture, keeping every call in place.
 * <p>
 * The Axon Framework 4 {@code AggregateTestFixture} ran a stub deadline manager on a simulated clock: tests asserted
 * scheduled, cancelled and triggered deadlines ({@code expectScheduledDeadline*}, {@code expectNoScheduledDeadline*},
 * {@code expectDeadlinesMet*}, {@code expectTriggeredDeadlines*}), moved the clock ({@code givenCurrentTime},
 * {@code andGivenCurrentTime}, {@code currentTime}, {@code whenTimeElapses}, {@code whenTimeAdvancesTo} and their
 * {@code whenThen*} and {@code andThen*} variants) and intercepted deadlines
 * ({@code registerDeadlineDispatchInterceptor}, {@code registerDeadlineHandlerInterceptor}). The Axon Framework 5
 * {@code AxonTestFixture} the aggregate fixture migrates to has none of this: no deadline manager and no clock. The
 * fluent rewrite of the surrounding chain therefore leaves these calls behind as code that does not compile, and how to
 * test the deadline instead depends on what the test meant to verify.
 * <p>
 * The marker text depends on where the migrated application runs its deadlines. Free Axon Framework 5 has none. On
 * Axoniq Framework, {@code axoniq-legacy} delivers a fired aggregate deadline as a command to the
 * {@code @CommandHandler} its {@code @DeadlineHandler} became, so a test can send that command itself; set
 * {@link #getAxoniqLegacy() axoniqLegacy} to say so. Both variants share the marker keys, so the free migration leaves
 * a statement alone that the commercial one marked first.
 * <p>
 * Runs on the Axon Framework 4 types, before they are renamed. Java sources only. Idempotent.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MarkAggregateTestFixtureDeadlineCalls extends Recipe {

    private static final String AGGREGATE_FIXTURE_PACKAGE = "org.axonframework.test.aggregate";
    private static final String SAGA_FIXTURE_PACKAGE = "org.axonframework.test.saga";
    private static final Set<String> DEADLINE_AND_TIME_CALLS = Set.of(
            "expectScheduledDeadline", "expectScheduledDeadlineMatching", "expectScheduledDeadlineOfType",
            "expectScheduledDeadlineWithName",
            "expectNoScheduledDeadlines", "expectNoScheduledDeadline", "expectNoScheduledDeadlineMatching",
            "expectNoScheduledDeadlineOfType", "expectNoScheduledDeadlineWithName",
            "expectDeadlinesMet", "expectDeadlinesMetMatching",
            "expectTriggeredDeadlines", "expectTriggeredDeadlinesMatching", "expectTriggeredDeadlinesWithName",
            "expectTriggeredDeadlinesOfType",
            "givenCurrentTime", "andGivenCurrentTime", "currentTime",
            "whenTimeElapses", "whenThenTimeElapses", "andThenTimeElapses",
            "whenTimeAdvancesTo", "whenThenTimeAdvancesTo", "andThenTimeAdvancesTo"
    );
    private static final Set<String> DEADLINE_INTERCEPTOR_CALLS = Set.of(
            "registerDeadlineDispatchInterceptor", "registerDeadlineHandlerInterceptor"
    );

    private static final String DEADLINE_TEST_KEY = "TODO(axon4to5): rewrite this aggregate deadline test";
    private static final String DEADLINE_TEST_PREFIX = " " + DEADLINE_TEST_KEY
            + " without the fixture's deadline and time calls. AxonTestFixture has no deadline manager and no clock"
            + " to advance";
    private static final StatementTodoMarker.Todo FREE_DEADLINE_TEST_TODO = new StatementTodoMarker.Todo(
            DEADLINE_TEST_KEY,
            DEADLINE_TEST_PREFIX + ", and Axon Framework 5 has no deadlines; Axoniq Framework keeps them running on"
                    + " axoniq-legacy."
    );
    private static final StatementTodoMarker.Todo LEGACY_DEADLINE_TEST_TODO = new StatementTodoMarker.Todo(
            DEADLINE_TEST_KEY,
            DEADLINE_TEST_PREFIX + ", and axoniq-legacy-test supports deadlines on SagaTestFixture only. A fired"
                    + " aggregate deadline is now the command its migrated @CommandHandler receives: send it with"
                    + " when().command(<deadline payload>, Metadata.with("
                    + "AggregateDeadlineEntityIdResolverDefinition.DESCRIPTOR_BASED_ID, <aggregate identifier>)),"
                    + " where a deadline without payload is a command named after, and carrying, the deadline name."
                    + " Assert the scheduling on a StubDeadlineManager registered as the DeadlineManager component."
    );

    private static final String DEADLINE_INTERCEPTOR_KEY =
            "TODO(axon4to5): move this deadline interceptor registration off the fixture";
    private static final StatementTodoMarker.Todo FREE_DEADLINE_INTERCEPTOR_TODO = new StatementTodoMarker.Todo(
            DEADLINE_INTERCEPTOR_KEY,
            " " + DEADLINE_INTERCEPTOR_KEY + ", or remove it. AxonTestFixture has no deadline manager to intercept,"
                    + " and Axon Framework 5 has no deadlines; Axoniq Framework keeps them running on axoniq-legacy."
    );
    private static final StatementTodoMarker.Todo LEGACY_DEADLINE_INTERCEPTOR_TODO = new StatementTodoMarker.Todo(
            DEADLINE_INTERCEPTOR_KEY,
            " " + DEADLINE_INTERCEPTOR_KEY + ". AxonTestFixture has no deadline manager to intercept; register the"
                    + " interceptor, in its Axon Framework 5 signature, on the deadline manager the test uses, such as"
                    + " a StubDeadlineManager."
    );

    @Option(displayName = "Deadlines on axoniq-legacy",
            description = "Whether the migrated application keeps its deadlines on Axoniq Framework's axoniq-legacy"
                    + " module, where a fired aggregate deadline arrives as a command. The marker then says how to"
                    + " test the deadline through that command.",
            required = false)
    private final @Nullable Boolean axoniqLegacy;

    /**
     * Creates the recipe, choosing the marker text for the platform the deadlines migrate to.
     *
     * @param axoniqLegacy whether the deadlines keep running on {@code axoniq-legacy}; {@code null} means they do not
     */
    @JsonCreator
    public MarkAggregateTestFixtureDeadlineCalls(@JsonProperty("axoniqLegacy") @Nullable Boolean axoniqLegacy) {
        this.axoniqLegacy = axoniqLegacy;
    }

    /**
     * Returns whether the migrated application keeps its deadlines on {@code axoniq-legacy}.
     *
     * @return whether the deadlines keep running on {@code axoniq-legacy}, or {@code null} when not set
     */
    public @Nullable Boolean getAxoniqLegacy() {
        return axoniqLegacy;
    }

    @Override
    public String getDisplayName() {
        return "Mark deadline and time calls on the aggregate test fixture";
    }

    @Override
    public String getDescription() {
        return "Leaves a `// TODO(axon4to5):` marker, keeping the code in place, on statements that use the deadline "
                + "and time support of the Axon Framework 4 `AggregateTestFixture`: the `expectScheduledDeadline*`, "
                + "`expectNoScheduledDeadline*`, `expectDeadlinesMet*` and `expectTriggeredDeadlines*` assertions, "
                + "the clock calls (`givenCurrentTime`, `whenTimeElapses`, `whenTimeAdvancesTo` and their variants), "
                + "and the deadline interceptor registrations. The Axon Framework 5 `AxonTestFixture` has no deadline "
                + "manager and no clock. With `axoniqLegacy`, the marker says how to test the deadline as the command "
                + "`axoniq-legacy` delivers it as. Java sources only. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        boolean legacy = Boolean.TRUE.equals(axoniqLegacy);
        StatementTodoMarker.Todo deadlineTestTodo = legacy ? LEGACY_DEADLINE_TEST_TODO : FREE_DEADLINE_TEST_TODO;
        StatementTodoMarker.Todo interceptorTodo =
                legacy ? LEGACY_DEADLINE_INTERCEPTOR_TODO : FREE_DEADLINE_INTERCEPTOR_TODO;
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.CompilationUnit unit = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                return StatementTodoMarker.markStatements(super.visitBlock(block, ctx), invocation -> {
                    if (!isAggregateFixtureCall(invocation, unit)) {
                        return null;
                    }
                    String name = invocation.getSimpleName();
                    if (DEADLINE_AND_TIME_CALLS.contains(name)) {
                        return deadlineTestTodo;
                    }
                    return DEADLINE_INTERCEPTOR_CALLS.contains(name) ? interceptorTodo : null;
                });
            }
        };
    }

    /**
     * Whether the invocation is declared by a type of the Axon Framework 4 aggregate fixture package. An unresolved
     * invocation counts when its source file imports from that package and not from the Saga fixture package, whose
     * fixture has methods of the same names.
     */
    private static boolean isAggregateFixtureCall(J.MethodInvocation invocation, J.CompilationUnit unit) {
        JavaType.Method methodType = invocation.getMethodType();
        if (methodType != null && !(methodType.getDeclaringType() instanceof JavaType.Unknown)) {
            return AGGREGATE_FIXTURE_PACKAGE.equals(methodType.getDeclaringType().getPackageName());
        }
        return importsFrom(unit, AGGREGATE_FIXTURE_PACKAGE) && !importsFrom(unit, SAGA_FIXTURE_PACKAGE);
    }

    private static boolean importsFrom(J.CompilationUnit unit, String packageName) {
        return unit.getImports().stream().anyMatch(anImport -> packageName.equals(anImport.getPackageName()));
    }
}
