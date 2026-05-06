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
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites AF4 {@code AggregateTestFixture} test method bodies to the AF5
 * {@code AxonTestFixture} fluent given/when/then API.
 * <p>
 * AF5 split the flat AF4 fixture API ({@code fixture.given(events).when(cmd).expectEvents(out)})
 * into three sub-builders reached through no-arg phase methods:
 * {@code fixture.given().events(events).when().command(cmd).then().events(out)}. The leaf method
 * names also changed (e.g. {@code expectNoEvents} → {@code noEvents},
 * {@code expectSuccessfulHandlerExecution} → {@code success}).
 * <p>
 * This recipe handles the mechanical chain rewrite. Rewrites applied:
 * <ul>
 *     <li>{@code given(e…)} → {@code given().events(e…)}</li>
 *     <li>{@code givenCommands(c)} → {@code given().command(c)} (single-arg);
 *         {@code givenCommands(c1, c2…)} → {@code given().commands(c1, c2…)} (multi-arg)</li>
 *     <li>{@code givenNoPriorActivity()} → {@code given().noPriorActivity()}</li>
 *     <li>{@code when(cmd)} / {@code when(cmd, md)} → {@code when().command(cmd[, md])}</li>
 *     <li>{@code expectEvents(e…)} → {@code then().events(e…)}</li>
 *     <li>{@code expectNoEvents()} → {@code then().noEvents()}</li>
 *     <li>{@code expectException(X.class)} → {@code then().exception(X.class)}</li>
 *     <li>{@code expectException(X.class).expectExceptionMessage(m)} → {@code then().exception(X.class, m)}</li>
 *     <li>{@code expectSuccessfulHandlerExecution()} → {@code then().success()}</li>
 *     <li>{@code expectResultMessagePayload(p)} → {@code then().resultMessagePayload(p)}</li>
 * </ul>
 * The fixture <b>setup</b> migration ({@code new AggregateTestFixture<>(...)} →
 * {@code AxonTestFixture.with(configurer)}, plus the {@code @AfterEach fixture.stop()} call)
 * stays manual: the AF5 fixture takes an {@code ApplicationConfigurer}, which is project-specific.
 * Hamcrest-matcher methods ({@code expectEventsMatching}, {@code expectResultMessageMatching}) are
 * also left alone — the AF4 matchers don't translate mechanically to AF5's
 * {@code Consumer}/{@code Predicate}-based {@code eventsSatisfy} / {@code eventsMatch}.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class MigrateAxonTestFixtureFluentApi extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate AggregateTestFixture calls to the AxonTestFixture fluent API";
    }

    @Override
    public String getDescription() {
        return "Rewrites the flat AF4 `fixture.given(...).when(...).expectEvents(...)` call shape to "
                + "the AF5 fluent `fixture.given().events(...).when().command(...).then().events(...)` "
                + "shape, including the leaf-method renames (`expectNoEvents` → `noEvents`, "
                + "`expectSuccessfulHandlerExecution` → `success`, `expectException` + "
                + "`expectExceptionMessage` → single `exception(cls, msg)`, etc.). The fixture setup "
                + "migration (constructor → `AxonTestFixture.with(configurer)`, `@AfterEach stop()`) and "
                + "Hamcrest matcher conversions stay manual.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);

                Expression select = mi.getSelect();
                if (select == null) {
                    return mi;
                }
                String name = mi.getSimpleName();
                List<Expression> args = realArgs(mi.getArguments());

                switch (name) {
                    case "expectEvents":
                        return wrapWithPhase(mi, "then", "events", select, args);
                    case "expectNoEvents":
                        return wrapWithPhase(mi, "then", "noEvents", select, args);
                    case "expectSuccessfulHandlerExecution":
                        return wrapWithPhase(mi, "then", "success", select, args);
                    case "expectResultMessagePayload":
                        return wrapWithPhase(mi, "then", "resultMessagePayload", select, args);
                    case "expectException":
                        return wrapWithPhase(mi, "then", "exception", select, args);
                    case "expectExceptionMessage":
                        // The select was just rewritten to `chain.then().exception(cls)`. Merge the
                        // message argument into that single `exception(cls, msg)` call so users get
                        // the AF5 two-arg form rather than chained `.exception(cls).expectExceptionMessage(msg)`.
                        if (select instanceof J.MethodInvocation
                                && "exception".equals(((J.MethodInvocation) select).getSimpleName())) {
                            J.MethodInvocation prior = (J.MethodInvocation) select;
                            List<Expression> merged = new ArrayList<>(realArgs(prior.getArguments()));
                            merged.addAll(args);
                            return prior.withArguments(merged);
                        }
                        return mi;
                    case "givenNoPriorActivity":
                        return wrapWithPhase(mi, "given", "noPriorActivity", select, args);
                    case "givenCommands":
                        return wrapWithPhase(mi, "given", args.size() == 1 ? "command" : "commands", select, args);
                    case "given":
                        // AF5's `given()` is no-arg; only the AF4 form (with arguments) needs rewriting.
                        return args.isEmpty()
                                ? mi
                                : wrapWithPhase(mi, "given", "events", select, args);
                    case "when":
                        // Same disambiguation as `given`: AF5 `when()` is no-arg.
                        return args.isEmpty()
                                ? mi
                                : wrapWithPhase(mi, "when", "command", select, args);
                    default:
                        return mi;
                }
            }

            /**
             * Rewrites {@code chain.oldName(args)} to {@code chain.phase().newName(args)}.
             * Builds a JavaTemplate string with one {@code #{any()}} placeholder per argument plus
             * one for the receiver chain, then applies it at the position of the original invocation.
             */
            private J.MethodInvocation wrapWithPhase(J.MethodInvocation mi,
                                                    String phase,
                                                    String newName,
                                                    Expression select,
                                                    List<Expression> args) {
                StringBuilder template = new StringBuilder("#{any()}.")
                        .append(phase)
                        .append("().")
                        .append(newName)
                        .append("(");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        template.append(", ");
                    }
                    template.append("#{any()}");
                }
                template.append(")");

                List<Object> templateArgs = new ArrayList<>(args.size() + 1);
                templateArgs.add(select);
                templateArgs.addAll(args);

                return JavaTemplate.builder(template.toString())
                        .contextSensitive()
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), mi.getCoordinates().replace(), templateArgs.toArray());
            }

            /**
             * OpenRewrite represents a no-arg invocation as a singleton list containing one
             * {@link J.Empty}. Strip that so callers can rely on {@code args.size() == 0} for "no args".
             */
            private List<Expression> realArgs(List<Expression> args) {
                if (args.size() == 1 && args.get(0) instanceof J.Empty) {
                    return List.of();
                }
                return args;
            }
        };
    }
}
