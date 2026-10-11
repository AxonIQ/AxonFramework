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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Set;

/**
 * Leaves a {@code // TODO(axon4to5):} marker on the Axon Framework 4 {@code SagaTestFixture} usage that the fixture
 * ported by {@code axoniq-legacy-test} no longer supports, keeping every call in place.
 * <p>
 * The ported fixture keeps the Axon Framework 4 API where Axon Framework 5 allows it. Where it does not, the test
 * either stops compiling or fails at run time, and what to do instead depends on what the test meant to verify, so
 * the recipe marks the statement and leaves the decision to the developer:
 * <ul>
 *     <li>{@code withTransienceCheckDisabled()} is a deprecated-for-removal no-op: Sagas get no field injection, so
 *     there is no transience check to disable;</li>
 *     <li>{@code expectScheduledEvent*}, {@code expectNoScheduledEvent*} and {@code expectNoScheduledEvents()} throw
 *     an {@link UnsupportedOperationException}, because the event scheduler is not ported;</li>
 *     <li>{@code registerCommandGateway(..)} and {@code registerListenerInvocationErrorHandler(..)} are removed, as
 *     Axon Framework 5 has neither a gateway factory nor a listener invocation error handler;</li>
 *     <li>a variable declared as the Saga {@code FixtureConfiguration} cannot be stopped, while the ported fixture runs
 *     a started configuration that must be stopped after each test;</li>
 *     <li>{@code givenAPublished}, {@code andThenAPublished}, {@code whenPublishingA} and {@code publishes} take
 *     metadata as a {@code Map<String, String>} instead of a {@code Map<String, ?>}, so passing a variable declared
 *     with other values, such as a {@code Map<String, Object>}, no longer compiles.</li>
 * </ul>
 * {@code registerResourceInjector(..)} is not handled here: {@link MarkSagaResourceInjection} marks it. The marker goes
 * on the enclosing statement, so a call inside a multi-line fixture chain is flagged above the chain. Java sources
 * only. Idempotent.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MarkUnsupportedSagaTestFixtureCalls extends Recipe {

    private static final String SAGA_FIXTURE_PACKAGE = "org.axonframework.test.saga";
    private static final String SAGA_FIXTURE_CONFIGURATION_FQN = SAGA_FIXTURE_PACKAGE + ".FixtureConfiguration";
    private static final Set<String> SAGA_FIXTURE_TYPES = Set.of(
            SAGA_FIXTURE_CONFIGURATION_FQN,
            SAGA_FIXTURE_PACKAGE + ".SagaTestFixture",
            SAGA_FIXTURE_PACKAGE + ".FixtureExecutionResult",
            SAGA_FIXTURE_PACKAGE + ".FixtureExecutionResultImpl"
    );
    private static final Set<String> SCHEDULED_EVENT_ASSERTIONS = Set.of(
            "expectScheduledEvent", "expectScheduledEventOfType", "expectScheduledEventMatching",
            "expectNoScheduledEvent", "expectNoScheduledEventOfType", "expectNoScheduledEventMatching",
            "expectNoScheduledEvents"
    );

    private static final Set<String> METADATA_PUBLICATIONS = Set.of(
            "givenAPublished", "andThenAPublished", "whenPublishingA", "publishes"
    );
    private static final String MAP_FQN = "java.util.Map";
    private static final String STRING_FQN = "java.lang.String";

    private static final StatementTodoMarker.Todo TRANSIENCE_CHECK_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): delete this withTransienceCheckDisabled() call",
            " TODO(axon4to5): delete this withTransienceCheckDisabled() call. It is a deprecated-for-removal no-op"
                    + " in axoniq-legacy-test: Sagas get no field injection, so there is no transience check to"
                    + " disable."
    );
    private static final StatementTodoMarker.Todo SCHEDULED_EVENT_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): remove or replace this scheduled event assertion",
            " TODO(axon4to5): remove or replace this scheduled event assertion. axoniq-legacy-test does not port"
                    + " the event scheduler, so expectScheduledEvent*, expectNoScheduledEvent* and"
                    + " expectNoScheduledEvents() throw UnsupportedOperationException."
    );
    private static final StatementTodoMarker.Todo COMMAND_GATEWAY_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): replace registerCommandGateway(..)",
            " TODO(axon4to5): replace registerCommandGateway(..), which axoniq-legacy-test does not have."
                    + " Axon Framework 5 cannot build a custom gateway interface; let the Saga take a"
                    + " CommandDispatcher or CommandGateway handler parameter, and answer its commands with"
                    + " setCallbackBehavior(..) or a command handler registered through customize(..)."
    );
    private static final StatementTodoMarker.Todo ERROR_HANDLER_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): replace registerListenerInvocationErrorHandler(..)",
            " TODO(axon4to5): replace registerListenerInvocationErrorHandler(..), which axoniq-legacy-test does"
                    + " not have. Axon Framework 5 has no ListenerInvocationErrorHandler; a Saga handles its own"
                    + " failures in an @ExceptionHandler method."
    );
    private static final StatementTodoMarker.Todo FIXTURE_CONFIGURATION_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): declare this fixture as SagaTestFixture",
            " TODO(axon4to5): declare this fixture as SagaTestFixture and call stop() on it in an @AfterEach"
                    + " method. The ported fixture runs a started configuration, and FixtureConfiguration has no"
                    + " stop()."
    );

    private static final StatementTodoMarker.Todo METADATA_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): pass this metadata as a Map<String, String>",
            " TODO(axon4to5): pass this metadata as a Map<String, String>. The axoniq-legacy-test fixture takes"
                    + " Axon Framework 5 metadata, whose values are Strings, where Axon Framework 4 took any"
                    + " Map<String, ?>."
    );

    @Override
    public String getDisplayName() {
        return "Mark unsupported SagaTestFixture calls";
    }

    @Override
    public String getDescription() {
        return "Leaves a `// TODO(axon4to5):` marker, keeping the code in place, on `SagaTestFixture` usage the "
                + "`axoniq-legacy-test` fixture no longer supports: the no-op `withTransienceCheckDisabled()`, the "
                + "scheduled event assertions that throw `UnsupportedOperationException`, the removed "
                + "`registerCommandGateway(..)` and `registerListenerInvocationErrorHandler(..)`, variables "
                + "declared as the Saga `FixtureConfiguration`, which cannot be stopped, and event publications "
                + "passing metadata declared as a map with non-String values, such as a `Map<String, Object>`, "
                + "where the ported fixture takes a `Map<String, String>`. Java sources only. "
                + "Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = StatementTodoMarker.markStatements(super.visitBlock(block, ctx),
                                                                     MarkUnsupportedSagaTestFixtureCalls::todoFor);
                return visited.withStatements(ListUtils.map(visited.getStatements(), statement ->
                        statement instanceof J.VariableDeclarations declarations
                                && TypeUtils.isOfClassType(declarations.getType(), SAGA_FIXTURE_CONFIGURATION_FQN)
                                ? StatementTodoMarker.mark(declarations, FIXTURE_CONFIGURATION_TODO)
                                : statement
                ));
            }
        };
    }

    private static StatementTodoMarker.@Nullable Todo todoFor(J.MethodInvocation invocation) {
        JavaType.Method methodType = invocation.getMethodType();
        if (methodType == null) {
            return null;
        }
        String name = invocation.getSimpleName();
        if (METADATA_PUBLICATIONS.contains(name)
                && SAGA_FIXTURE_PACKAGE.equals(methodType.getDeclaringType().getPackageName())
                && invocation.getArguments().size() == 2
                && declaresNonStringMapValues(invocation.getArguments().get(1))) {
            return METADATA_TODO;
        }
        if (!SAGA_FIXTURE_TYPES.contains(methodType.getDeclaringType().getFullyQualifiedName())) {
            return null;
        }
        if (SCHEDULED_EVENT_ASSERTIONS.contains(name)) {
            return SCHEDULED_EVENT_TODO;
        }
        return switch (name) {
            case "withTransienceCheckDisabled" -> TRANSIENCE_CHECK_TODO;
            case "registerCommandGateway" -> COMMAND_GATEWAY_TODO;
            case "registerListenerInvocationErrorHandler" -> ERROR_HANDLER_TODO;
            default -> null;
        };
    }

    /**
     * Whether the metadata argument is a variable declared as a {@link java.util.Map} whose value type is not
     * {@link String}, such as {@code Map<String, Object>} or {@code Map<String, ?>}. Only a declared variable proves
     * the argument no longer fits; a map built in place, such as {@code Map.of("key", "value")}, infers its value type
     * from the parameter it is passed to, and {@code MetaData} becomes the Axon Framework 5 {@code Metadata}, whose
     * values are Strings.
     */
    private static boolean declaresNonStringMapValues(Expression argument) {
        JavaType.Variable variable = argument instanceof J.Identifier identifier
                ? identifier.getFieldType()
                : argument instanceof J.FieldAccess fieldAccess ? fieldAccess.getName().getFieldType() : null;
        if (variable == null
                || !(variable.getType() instanceof JavaType.Parameterized declared)
                || !TypeUtils.isAssignableTo(MAP_FQN, declared)
                || declared.getTypeParameters().size() != 2) {
            return false;
        }
        JavaType valueType = declared.getTypeParameters().get(1);
        return !(valueType instanceof JavaType.Unknown) && !TypeUtils.isOfClassType(valueType, STRING_FQN);
    }
}
