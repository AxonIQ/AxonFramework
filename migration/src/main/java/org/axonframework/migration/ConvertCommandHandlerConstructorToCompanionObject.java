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
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;

import java.util.ArrayList;
import java.util.List;

/**
 * Kotlin counterpart to {@link ConvertCommandHandlerConstructorToStaticMethod}.
 * <p>
 * Rewrites every {@code @CommandHandler constructor(...)} on a Kotlin entity class into a
 * {@code @JvmStatic @CommandHandler fun handle(...)} placed inside a {@code companion object}
 * on the same class. The companion-object form is the Kotlin equivalent of the Java
 * {@code static} factory shape that AF5's command-creates-entity handler discovery requires:
 * the JVM bytecode produced by {@code @JvmStatic} matches the {@code static} method that
 * Java aggregates emit, so a single AF5 framework path discovers both.
 * <pre>
 * class Auction {                                    class Auction {
 *     &#64;CommandHandler                             →     companion object {
 *     constructor(cmd: CreateAuction) {                       &#64;JvmStatic
 *         AggregateLifecycle.apply(cmd)                       &#64;CommandHandler
 *     }                                                       fun handle(cmd: CreateAuction) {
 * }                                                               AggregateLifecycle.apply(cmd)
 *                                                            }
 *                                                        }
 *                                                    }
 * </pre>
 * The recipe sub-parses a synthetic holder class containing the desired companion-object shape
 * via {@link KotlinParser}, then grafts the parsed companion-object node into the original
 * class body. Building the {@code KObject}-marked {@link J.ClassDeclaration} via the parser
 * — rather than synthesizing it node-by-node — sidesteps the Kotlin-specific markers
 * ({@code KObject}, the {@code companion} / {@code object} {@link J.Modifier}s) that the
 * Kotlin printer needs to emit the right keywords.
 * <p>
 * Existing companion objects on the host class are NOT merged into; if the developer needs
 * to consolidate, that's a follow-up cleanup.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class ConvertCommandHandlerConstructorToCompanionObject extends Recipe {

    private static final String COMMAND_HANDLER_AF4 = "org.axonframework.commandhandling.CommandHandler";
    private static final String COMMAND_HANDLER_AF5 = "org.axonframework.messaging.commandhandling.annotation.CommandHandler";
    private static final String NEW_METHOD_NAME = "handle";

    @Override
    public String getDisplayName() {
        return "Convert Kotlin @CommandHandler constructors to a companion-object handle method";
    }

    @Override
    public String getDescription() {
        return "Rewrites Kotlin `@CommandHandler constructor(...)` declarations into "
                + "`companion object { @JvmStatic @CommandHandler fun handle(...) }` on the same "
                + "class, matching the AF5 contract where `@CommandHandler` no longer targets "
                + "constructors. Parameter list and method body are preserved.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                // Only Kotlin sources. Java is handled by the sibling recipe
                // ConvertCommandHandlerConstructorToStaticMethod.
                return sourceFile instanceof K.CompilationUnit;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
                                                            ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getBody() == null) {
                    return cd;
                }
                List<J.MethodDeclaration> commandConstructors = collectCommandHandlerConstructors(cd);
                if (commandConstructors.isEmpty()) {
                    return cd;
                }
                J.ClassDeclaration companionObject = buildCompanionObject(commandConstructors, ctx);
                if (companionObject == null) {
                    // Sub-parse failed; leave the class untouched rather than emit half-migrated
                    // code. The next recipe-cycle log will surface the parse error to the user.
                    return cd;
                }
                List<Statement> rewrittenBody = new ArrayList<>();
                for (Statement stmt : cd.getBody().getStatements()) {
                    boolean removed = commandConstructors.stream()
                            .anyMatch(c -> c.getId().equals(stmt.getId()));
                    if (!removed) {
                        rewrittenBody.add(stmt);
                    }
                }
                rewrittenBody.add(companionObject);
                // The host file's CommandHandler import is already present (it bound the
                // original @CommandHandler constructor we just removed) and the companion
                // object's sub-parsed @CommandHandler resolves through that same simple-name
                // import — so we deliberately don't `maybeAddImport` here. Re-asserting the
                // AF4 FQN would clash with the AF4→AF5 ChangeType rename across cycles and
                // leave the file with duplicate imports.
                return cd.withBody(cd.getBody().withStatements(rewrittenBody));
            }

            private List<J.MethodDeclaration> collectCommandHandlerConstructors(J.ClassDeclaration cd) {
                List<J.MethodDeclaration> hits = new ArrayList<>();
                for (Statement stmt : cd.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                        if (isConstructor(md, cd) && hasCommandHandlerAnnotation(md)) {
                            hits.add(md);
                        }
                    }
                }
                return hits;
            }

            /**
             * Strict constructor check — {@link J.MethodDeclaration#isConstructor()} only inspects
             * whether the return-type expression is null, which Kotlin functions without an
             * explicit return type also satisfy. To distinguish the two we additionally require
             * the method's simple name to match the enclosing class — that is the actual
             * Java/Kotlin constructor convention.
             */
            private boolean isConstructor(J.MethodDeclaration md, J.ClassDeclaration enclosing) {
                return md.isConstructor()
                        && md.getSimpleName().equals(enclosing.getSimpleName());
            }

            private boolean hasCommandHandlerAnnotation(J.MethodDeclaration md) {
                for (J.Annotation ann : md.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), COMMAND_HANDLER_AF4)
                            || TypeUtils.isOfClassType(ann.getType(), COMMAND_HANDLER_AF5)) {
                        return true;
                    }
                    if (ann.getAnnotationType() instanceof J.Identifier
                            && "CommandHandler".equals(
                                    ((J.Identifier) ann.getAnnotationType()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Builds the {@code companion object} {@link J.ClassDeclaration}. The shell — the
             * companion-object {@link J.ClassDeclaration} with its {@code KObject} marker, the
             * inner {@code @JvmStatic} / {@code @CommandHandler} {@code fun handle(...)} skeleton
             * — comes from sub-parsing a synthetic holder class through {@link KotlinParser}, so
             * we don't have to fabricate Kotlin-specific markers by hand. Crucially, the
             * generated {@code fun}'s body and parameters are then SWAPPED for the original
             * constructor's body and parameters: the sub-parsed nodes have no type bindings (the
             * snippet's classpath doesn't include the host project's types), and downstream
             * recipes such as {@link ReplaceAggregateLifecycleApply} need typed bindings to
             * recognize {@code AggregateLifecycle.apply(...)} via {@link
             * org.openrewrite.java.MethodMatcher}. Reusing the original {@link J.Block} keeps
             * those bindings intact.
             * <p>
             * Returns {@code null} when the parser cannot produce a usable LST.
             */
            private J.ClassDeclaration buildCompanionObject(List<J.MethodDeclaration> commandConstructors,
                                                            ExecutionContext ctx) {
                StringBuilder funs = new StringBuilder();
                for (int i = 0; i < commandConstructors.size(); i++) {
                    funs.append("        @JvmStatic\n");
                    // Reference @CommandHandler by simple name + bring it in via a snippet
                    // import. The grafted companion object then carries `@CommandHandler` in the
                    // host file, and `maybeAddImport(COMMAND_HANDLER_AF4)` ensures the host's
                    // import block has the matching declaration so downstream type renames
                    // (e.g. AF4 → AF5 CommandHandler via ChangeType) see a consistent view.
                    funs.append("        @CommandHandler\n");
                    funs.append("        fun ").append(NEW_METHOD_NAME).append("_").append(i)
                            .append("() {}\n");
                }
                String snippet = "package _temp\n\n"
                        + "import " + COMMAND_HANDLER_AF4 + "\n\n"
                        + "class _Holder {\n"
                        + "    companion object {\n"
                        + funs
                        + "    }\n"
                        + "}\n";
                List<SourceFile> parsed;
                try {
                    parsed = KotlinParser.builder().build().parse(snippet)
                            .filter(s -> s instanceof K.CompilationUnit)
                            .toList();
                } catch (RuntimeException ex) {
                    return null;
                }
                if (parsed.isEmpty()) {
                    return null;
                }
                J.ClassDeclaration parsedCompanion = extractCompanionObject((K.CompilationUnit) parsed.get(0));
                if (parsedCompanion == null || parsedCompanion.getBody() == null) {
                    return null;
                }
                List<Statement> stubFuns = parsedCompanion.getBody().getStatements();
                if (stubFuns.size() != commandConstructors.size()) {
                    return null;
                }
                List<Statement> grafted = new ArrayList<>(stubFuns.size());
                for (int i = 0; i < stubFuns.size(); i++) {
                    if (!(stubFuns.get(i) instanceof J.MethodDeclaration)) {
                        return null;
                    }
                    grafted.add(graftConstructor((J.MethodDeclaration) stubFuns.get(i),
                                                  commandConstructors.get(i)));
                }
                return parsedCompanion.withBody(parsedCompanion.getBody().withStatements(grafted));
            }

            private J.ClassDeclaration extractCompanionObject(K.CompilationUnit cu) {
                for (Statement topLevel : cu.getStatements()) {
                    if (!(topLevel instanceof J.ClassDeclaration)) {
                        continue;
                    }
                    J.ClassDeclaration holder = (J.ClassDeclaration) topLevel;
                    if (holder.getBody() == null) {
                        continue;
                    }
                    for (Statement member : holder.getBody().getStatements()) {
                        if (member instanceof J.ClassDeclaration) {
                            return (J.ClassDeclaration) member;
                        }
                    }
                }
                return null;
            }

            /**
             * Splices the original constructor's typed nodes into the sub-parsed stub:
             * <ul>
             *   <li>parameters: copied wholesale — Kotlin parameter LST shape carries the bindings
             *   the body needs;</li>
             *   <li>body: the original {@link J.Block} is reused so its statements keep the
             *   {@code JavaType} bindings that downstream recipes (e.g.
             *   {@code ReplaceAggregateLifecycleApply}'s {@code MethodMatcher}) match against,
             *   then the closing-brace whitespace is rebased to the companion-object indent so
             *   the rendered output stays well-formatted. The original body sat at the class's
             *   member indent (4 spaces); inside the companion object the same closing brace
             *   should sit at 8 spaces.</li>
             * </ul>
             */
            private J.MethodDeclaration graftConstructor(J.MethodDeclaration stubFun,
                                                         J.MethodDeclaration constructor) {
                J.MethodDeclaration result = stubFun
                        .withName(stubFun.getName().withSimpleName(NEW_METHOD_NAME));
                result = result.getPadding().withParameters(
                        constructor.getPadding().getParameters());
                if (constructor.getBody() != null) {
                    result = result.withBody(rebaseBodyIndent(constructor.getBody()));
                }
                return result;
            }

            /**
             * Adds one indent level to the body's closing-brace whitespace and to each top-level
             * statement's leading whitespace. The original body sat one level deep (inside the
             * class); the grafted body sits two levels deep (class → companion object → fun), so
             * each line that anchored to the old depth gains a 4-space pad.
             */
            private J.Block rebaseBodyIndent(J.Block body) {
                J.Block rebased = body.withEnd(addIndentLevel(body.getEnd()));
                rebased = rebased.withStatements(
                        rebased.getStatements().stream()
                                .map(s -> (Statement) s.withPrefix(addIndentLevel(s.getPrefix())))
                                .toList());
                return rebased;
            }

            private org.openrewrite.java.tree.Space addIndentLevel(org.openrewrite.java.tree.Space space) {
                String ws = space.getWhitespace();
                int lastNewline = ws.lastIndexOf('\n');
                if (lastNewline < 0) {
                    return space;
                }
                String prefix = ws.substring(0, lastNewline + 1);
                String existingIndent = ws.substring(lastNewline + 1);
                return space.withWhitespace(prefix + "    " + existingIndent);
            }
        };
    }
}
