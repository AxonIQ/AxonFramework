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

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.kotlin.tree.K;

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
                // Kotlin source can carry a method name backtick-escaped to dodge keyword
                // collisions (e.g. `` `when` `` for the AF5 phase entrypoint). The recipe's
                // dispatch and idempotency checks are language-agnostic, so strip backticks
                // before comparing to AF4/AF5 names.
                String name = unescapeBackticks(mi.getSimpleName());
                List<Expression> args = realArgs(mi.getArguments());

                switch (name) {
                    case "expectEvents":
                        return wrapOrRename(mi, "then", "events", select, args);
                    case "expectNoEvents":
                        return wrapOrRename(mi, "then", "noEvents", select, args);
                    case "expectSuccessfulHandlerExecution":
                        return wrapOrRename(mi, "then", "success", select, args);
                    case "expectResultMessagePayload":
                        return wrapOrRename(mi, "then", "resultMessagePayload", select, args);
                    case "expectException":
                        return wrapOrRename(mi, "then", "exception", select, args);
                    case "expectExceptionMessage":
                        // The select was just rewritten to `chain.then().exception(cls)`. Merge the
                        // message argument into that single `exception(cls, msg)` call so users get
                        // the AF5 two-arg form rather than chained `.exception(cls).expectExceptionMessage(msg)`.
                        if (select instanceof J.MethodInvocation
                                && "exception".equals(unescapeBackticks(
                                        ((J.MethodInvocation) select).getSimpleName()))) {
                            J.MethodInvocation prior = (J.MethodInvocation) select;
                            List<Expression> existing = realArgs(prior.getArguments());
                            List<Expression> merged = new ArrayList<>(existing);
                            // Re-prefix the appended message argument with `" "` so it renders as
                            // `exception(cls, msg)` rather than `exception(cls,msg)` after the comma
                            // separator that JContainer inserts between arguments.
                            for (Expression added : args) {
                                merged.add(added.withPrefix(
                                        org.openrewrite.java.tree.Space.format(" ")));
                            }
                            // Lift the outer invocation's prefix onto the merged call so the chain
                            // line that used to host `.expectExceptionMessage(...)` retains the
                            // expression-statement's original leading newline+indent.
                            return prior.withArguments(merged).withPrefix(mi.getPrefix());
                        }
                        return mi;
                    case "givenNoPriorActivity":
                        return wrapOrRename(mi, "given", "noPriorActivity", select, args);
                    case "givenCommands":
                        return wrapOrRename(mi, "given", args.size() == 1 ? "command" : "commands", select, args);
                    case "given":
                        // AF5's `given()` is no-arg; only the AF4 form (with arguments) needs rewriting.
                        return args.isEmpty()
                                ? mi
                                : wrapOrRename(mi, "given", "events", select, args);
                    case "when":
                        // Same disambiguation as `given`: AF5 `when()` is no-arg.
                        return args.isEmpty()
                                ? mi
                                : wrapOrRename(mi, "when", "command", select, args);
                    default:
                        return mi;
                }
            }

            /**
             * Coalesces same-phase chains. AF4 lets you stack multiple {@code expect*} calls
             * ({@code .expectNoEvents().expectException(X)}); rewriting each independently with
             * {@link #wrapWithPhase} would emit a duplicate phase entrypoint
             * ({@code .then().noEvents().then().exception(X)}). When the select chain already
             * contains the target phase as a no-arg call, just rename the leaf in place — keeping
             * a single {@code .then()} (or {@code .given()}/{@code .when()}) shared by all peers
             * under it ({@code .then().noEvents().exception(X)}).
             */
            private J.MethodInvocation wrapOrRename(J.MethodInvocation mi,
                                                    String phase,
                                                    String newName,
                                                    Expression select,
                                                    List<Expression> args) {
                if (phaseAlreadyInChain(select, phase)) {
                    return mi.withName(mi.getName().withSimpleName(newName));
                }
                return wrapWithPhase(mi, phase, newName, select, args);
            }

            /**
             * Walks the select chain looking for an AF5 phase entrypoint with the given name —
             * a no-arg invocation of {@code given}/{@code when}/{@code then}. Returns true as soon
             * as one is found; false if the walk reaches a non-MI receiver (typically the fixture
             * identifier) without seeing the phase. Used to detect already-rewritten same-phase
             * peers so we don't stack a redundant phase entrypoint on top of them.
             */
            private boolean phaseAlreadyInChain(Expression select, String phase) {
                Expression current = select;
                while (current instanceof J.MethodInvocation) {
                    J.MethodInvocation invocation = (J.MethodInvocation) current;
                    if (phase.equals(unescapeBackticks(invocation.getSimpleName()))
                            && realArgs(invocation.getArguments()).isEmpty()) {
                        return true;
                    }
                    current = invocation.getSelect();
                }
                return false;
            }

            /**
             * Rewrites {@code chain.oldName(args)} to {@code chain.phase().newName(args)} while
             * preserving — and extending — the source chain's line layout.
             * <p>
             * Each AF4 leaf call (e.g. {@code .given(events)}, {@code .when(cmd)},
             * {@code .expectEvents(out)}) is split into two AF5 calls: a no-arg phase entrypoint
             * ({@code .given()}, {@code .when()}, {@code .then()}) followed by a typed leaf
             * ({@code .events(...)}, {@code .command(...)}, etc.). To keep the result readable in
             * multi-line chains we put each call on its own line at the chain's existing indent:
             * <pre>{@code
             * fixture.given()
             *        .events(events)
             *        .when()
             *        .command(cmd)
             *        .then()
             *        .events(out);
             * }</pre>
             * <p>
             * The phase call inherits {@code mi}'s original pre-dot whitespace (so {@code .given()}
             * lands exactly where {@code .given(events)} used to be — typically inline with
             * {@code fixture}, no leading newline). The leaf call uses the chain's indent: if
             * {@code mi}'s own pre-dot whitespace contains a newline (i.e. it is itself on its own
             * line), reuse that; otherwise walk up the cursor to find an enclosing chain MI that
             * already sits on its own line and copy its indent. For pure single-line chains the
             * fallback yields {@link Space#EMPTY} and the result stays single-line.
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

                // Build the new chain manually so we can preserve the original whitespace before the
                // dot of the rewritten call. JavaTemplate normalizes chain whitespace, which collapses
                // multi-line fluent builders onto a single line.
                //
                // Layout of `<select>.phase().newName(args)`:
                //   outer = MethodInvocation(select=phaseCall, name=newName, args=[args...])
                //   phaseCall = MethodInvocation(select=originalSelect, name=phase, args=[])
                //
                // Whitespace mapping:
                //   - phaseCall's prefix = mi's prefix (often empty for chain elements)
                //   - phaseCall's select right-padding (whitespace before the dot of `.phase`)
                //     = mi's original select right-padding (the newline+indent before `.<oldName>`)
                //   - outer's select right-padding (whitespace between `.phase()` and `.newName(`)
                //     = chain indent — reuses mi's own pre-dot whitespace when it has a newline,
                //       otherwise inherits from the nearest enclosing chain MI that lives on its
                //       own line. Empty fallback preserves true single-line chains.
                //   - outer's name prefix = empty
                Space dotBeforePhase = mi.getPadding().getSelect() != null
                        ? mi.getPadding().getSelect().getAfter()
                        : Space.EMPTY;
                Space dotBeforeLeaf = chainIndent(mi, dotBeforePhase);
                org.openrewrite.java.tree.JRightPadded<Expression> selectForPhase =
                        org.openrewrite.java.tree.JRightPadded.<Expression>build(select)
                                .withAfter(dotBeforePhase);
                // `when` is a Kotlin hard keyword — to call it as a method name in a Kotlin
                // source, the identifier must be backtick-escaped (`` `when` ``). The Kotlin
                // printer renders the identifier's simpleName verbatim, so the escape lives in
                // the LST. The leaf-method names (`events`, `command`, `noEvents`, …) are not
                // Kotlin keywords, so only the phase identifier needs the treatment.
                String phaseSimpleName = (isKotlinSource() && "when".equals(phase))
                        ? "`when`"
                        : phase;
                J.Identifier phaseName = new J.Identifier(
                        org.openrewrite.Tree.randomId(),
                        Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        java.util.Collections.emptyList(),
                        phaseSimpleName,
                        null,
                        null);
                J.MethodInvocation phaseCall = new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        selectForPhase,
                        null,
                        phaseName,
                        org.openrewrite.java.tree.JContainer.empty(),
                        null);
                org.openrewrite.java.tree.JRightPadded<Expression> selectForOuter =
                        org.openrewrite.java.tree.JRightPadded.<Expression>build(phaseCall)
                                .withAfter(dotBeforeLeaf);
                J.Identifier outerName = new J.Identifier(
                        org.openrewrite.Tree.randomId(),
                        Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        java.util.Collections.emptyList(),
                        newName,
                        null,
                        null);
                org.openrewrite.java.tree.JContainer<Expression> argContainer = args.isEmpty()
                        ? org.openrewrite.java.tree.JContainer.empty()
                        : org.openrewrite.java.tree.JContainer.build(
                                Space.EMPTY,
                                args.stream()
                                        .map(org.openrewrite.java.tree.JRightPadded::<Expression>build)
                                        .toList(),
                                org.openrewrite.marker.Markers.EMPTY);
                return new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        mi.getPrefix(),
                        org.openrewrite.marker.Markers.EMPTY,
                        selectForOuter,
                        null,
                        outerName,
                        argContainer,
                        null);
            }

            /**
             * Resolves the whitespace to insert before the leaf-method dot in a rewritten chain.
             * <p>
             * Returns {@code own} when it already encodes a newline+indent — i.e. {@code mi} itself
             * sits on its own line, so the same whitespace is the chain's indent and reusing it
             * keeps the leaf aligned with peer chain calls. Otherwise walks up the cursor looking
             * for an enclosing {@link J.MethodInvocation} whose pre-dot whitespace contains a
             * newline; that ancestor is on its own line, and its indent is the chain's indent.
             * Falls back to {@link Space#EMPTY} for genuinely single-line chains so nothing is
             * forced onto a new line.
             */
            private Space chainIndent(J.MethodInvocation mi, Space own) {
                if (containsNewline(own)) {
                    return own;
                }
                Cursor c = getCursor().getParent();
                while (c != null) {
                    Object value = c.getValue();
                    if (value instanceof J.MethodInvocation) {
                        J.MethodInvocation enclosing = (J.MethodInvocation) value;
                        Space candidate = enclosing.getPadding().getSelect() != null
                                ? enclosing.getPadding().getSelect().getAfter()
                                : Space.EMPTY;
                        if (containsNewline(candidate)) {
                            return candidate;
                        }
                    }
                    c = c.getParent();
                }
                return Space.EMPTY;
            }

            private boolean containsNewline(Space space) {
                String ws = space.getWhitespace();
                return ws != null && ws.indexOf('\n') >= 0;
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

            /**
             * Strips the leading and trailing backtick from a Kotlin-escaped identifier
             * (e.g. {@code `when`} → {@code when}). Returns the input unchanged for
             * regular identifiers. Used so name-based dispatch and idempotency checks
             * treat the escaped and unescaped forms as equivalent — the recipe's
             * semantic identity is the bare keyword.
             */
            private String unescapeBackticks(String name) {
                if (name.length() >= 2 && name.charAt(0) == '`'
                        && name.charAt(name.length() - 1) == '`') {
                    return name.substring(1, name.length() - 1);
                }
                return name;
            }

            private boolean isKotlinSource() {
                return getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit;
            }
        };
    }
}
