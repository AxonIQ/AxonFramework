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
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites Kotlin DSL Spring Dependency Management {@code mavenBom(...)}
 * imports whose version is supplied via Kotlin string-template indirection
 * (typically {@code ${property("axonFrameworkVersion")}} read from an
 * {@code extra["axonFrameworkVersion"]} declaration). Optionally updates the
 * matching {@code extra["..."]} literal to the AF5-line version in the same
 * pass.
 * <p>
 * <strong>Why not {@code gradle.ChangeManagedDependency}?</strong> The stock
 * trait that backs that recipe (
 * {@code SpringDependencyManagementPluginEntry.Matcher}) only extracts a
 * version variable from {@code K.StringTemplate} when the embedded
 * expression is a bare {@link J.Identifier}. Method-invocation forms like
 * {@code property("name")} / {@code findProperty("name")} are recognized
 * for Groovy GStrings but not for Kotlin string templates, so the trait
 * silently no-ops on the Cinema-style fixture
 * <pre>{@code
 * extra["axonFrameworkVersion"] = "4.12.1"
 * dependencyManagement {
 *     imports {
 *         mavenBom("org.axonframework:axon-bom:${property("axonFrameworkVersion")}")
 *     }
 * }
 * }</pre>
 * This recipe fills that gap with a small, Kotlin-DSL-only visitor that
 * matches the {@code mavenBom(...)} method-invocation node directly and
 * rewrites the literal {@code groupId:artifactId:} prefix in the
 * string-template's leading literal piece. The {@code ${...}} indirection
 * is preserved verbatim.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class MigrateKotlinDslBomImport extends Recipe {

    @Option(displayName = "Old groupId",
            description = "groupId of the BOM import to rewrite.",
            example = "org.axonframework")
    private final String oldGroupId;

    @Option(displayName = "Old artifactId",
            description = "artifactId of the BOM import to rewrite.",
            example = "axon-bom")
    private final String oldArtifactId;

    @Option(displayName = "New groupId",
            description = "groupId to rewrite the import to.",
            example = "org.axonframework")
    private final String newGroupId;

    @Option(displayName = "New artifactId",
            description = "artifactId to rewrite the import to.",
            example = "axon-framework-bom")
    private final String newArtifactId;

    @Option(displayName = "Extra property key",
            description = "Optional key of an `extra[\"...\"]` declaration whose "
                    + "literal string value should be updated to `newVersion`. "
                    + "Leave unset to skip the property update step (e.g. when the "
                    + "version is hard-coded into the `mavenBom(...)` call instead "
                    + "of read via `${property(...)}`).",
            example = "axonFrameworkVersion",
            required = false)
    private final @Nullable String extraPropertyKey;

    @Option(displayName = "New version",
            description = "Replacement value for the matching `extra[\"...\"]` "
                    + "string literal. Required if `extraPropertyKey` is set; "
                    + "ignored otherwise.",
            example = "5.1.1-SNAPSHOT",
            required = false)
    private final @Nullable String newVersion;

    @JsonCreator
    public MigrateKotlinDslBomImport(
            @JsonProperty("oldGroupId") String oldGroupId,
            @JsonProperty("oldArtifactId") String oldArtifactId,
            @JsonProperty("newGroupId") String newGroupId,
            @JsonProperty("newArtifactId") String newArtifactId,
            @JsonProperty("extraPropertyKey") @Nullable String extraPropertyKey,
            @JsonProperty("newVersion") @Nullable String newVersion) {
        this.oldGroupId = oldGroupId;
        this.oldArtifactId = oldArtifactId;
        this.newGroupId = newGroupId;
        this.newArtifactId = newArtifactId;
        this.extraPropertyKey = extraPropertyKey;
        this.newVersion = newVersion;
    }

    @Override
    public String getDisplayName() {
        return "Migrate Kotlin DSL `mavenBom(...)` import (with property indirection)";
    }

    @Override
    public String getDescription() {
        return "Swaps the `groupId:artifactId` prefix of a Spring Dependency "
                + "Management `mavenBom(\"g:a:${property(\"...\")}\")` call in a "
                + "`build.gradle.kts` script, leaving the `${property(...)}` "
                + "indirection in place. Optionally updates the literal value of "
                + "the matching `extra[\"...\"]` declaration so the version follows "
                + "the new BOM. Targets a gap in `gradle.ChangeManagedDependency` "
                + "where the Kotlin string-template variant of `property(...)` is "
                + "not recognized.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        String oldPrefix = oldGroupId + ":" + oldArtifactId + ":";
        String newPrefix = newGroupId + ":" + newArtifactId + ":";

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if ("mavenBom".equals(m.getSimpleName()) && !m.getArguments().isEmpty()) {
                    Expression first = m.getArguments().get(0);
                    if (first instanceof K.StringTemplate) {
                        Expression rewritten = rewriteBomTemplate((K.StringTemplate) first, oldPrefix, newPrefix);
                        if (rewritten != first) {
                            List<Expression> newArgs = new ArrayList<>(m.getArguments());
                            newArgs.set(0, rewritten);
                            return m.withArguments(newArgs);
                        }
                    }
                }
                return m;
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = super.visitAssignment(assignment, ctx);
                if (extraPropertyKey != null && newVersion != null) {
                    return maybeUpdateExtraSubscriptAssignment(a, extraPropertyKey, newVersion);
                }
                return a;
            }
        };
    }

    /**
     * Rewrites the leading literal piece of a Kotlin string template to swap a
     * {@code groupId:artifactId:} prefix while preserving any embedded
     * expressions ({@code ${property("...")}} etc.). Returns the original
     * template untouched when the prefix doesn't match.
     */
    private static Expression rewriteBomTemplate(K.StringTemplate template, String oldPrefix, String newPrefix) {
        List<J> parts = template.getStrings();
        if (parts.isEmpty() || !(parts.get(0) instanceof J.Literal)) {
            return template;
        }
        J.Literal head = (J.Literal) parts.get(0);
        Object headValue = head.getValue();
        if (!(headValue instanceof String)) {
            return template;
        }
        String headString = (String) headValue;
        if (!headString.startsWith(oldPrefix)) {
            return template;
        }
        String newHeadValue = newPrefix + headString.substring(oldPrefix.length());
        // Adjust valueSource (the source-text representation) when the parser
        // has populated it; falls back to the literal value otherwise.
        String oldSource = head.getValueSource();
        String newSource = oldSource == null ? newHeadValue : oldSource.replace(oldPrefix, newPrefix);
        J.Literal newHead = head.withValue(newHeadValue).withValueSource(newSource);
        List<J> newParts = new ArrayList<>(parts);
        newParts.set(0, newHead);
        return template.withStrings(newParts);
    }

    /**
     * Recognizes the {@code extra["KEY"] = "VALUE"} Kotlin DSL idiom and
     * rewrites the value literal when the key matches. The Kotlin parser
     * desugars indexed assignment (`a[k] = v`) into a {@code J.Assignment}
     * whose variable is a {@code J.MethodInvocation} representing the
     * indexed read on {@code extra}, and whose assignment is the value
     * expression. We pattern-match on that exact shape so a subscript
     * assignment on something other than {@code extra} is left untouched.
     */
    private static J.Assignment maybeUpdateExtraSubscriptAssignment(
            J.Assignment assignment, String key, String newValue) {
        Expression variable = assignment.getVariable();
        if (!(variable instanceof J.MethodInvocation)) {
            return assignment;
        }
        J.MethodInvocation indexed = (J.MethodInvocation) variable;
        // The synthetic method name for `a[k]` lookup varies between
        // OpenRewrite Kotlin parser versions ("get"/"invoke"/empty-name);
        // the load-bearing check is that the receiver is the `extra`
        // identifier and the single argument is the matching string key.
        Expression receiver = indexed.getSelect();
        if (!(receiver instanceof J.Identifier)
                || !"extra".equals(((J.Identifier) receiver).getSimpleName())) {
            return assignment;
        }
        if (indexed.getArguments().size() != 1) {
            return assignment;
        }
        Expression keyArg = indexed.getArguments().get(0);
        if (!(keyArg instanceof J.Literal)) {
            return assignment;
        }
        Object keyValue = ((J.Literal) keyArg).getValue();
        if (!(keyValue instanceof String) || !key.equals(keyValue)) {
            return assignment;
        }
        if (!(assignment.getAssignment() instanceof J.Literal)) {
            return assignment;
        }
        J.Literal valueLiteral = (J.Literal) assignment.getAssignment();
        Object oldValue = valueLiteral.getValue();
        if (!(oldValue instanceof String) || newValue.equals(oldValue)) {
            return assignment;
        }
        String oldSource = valueLiteral.getValueSource();
        String newSource = oldSource == null ? "\"" + newValue + "\"" : oldSource.replace((String) oldValue, newValue);
        J.Literal newValueLiteral = valueLiteral.withValue(newValue).withValueSource(newSource);
        return assignment.withAssignment(newValueLiteral);
    }
}
