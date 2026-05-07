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
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.java.dependencies.UpgradeDependencyVersion;
import org.openrewrite.maven.UpgradePluginVersion;

import java.util.List;

/**
 * Bumps Kotlin to a configurable 2.x line, with validation that the target satisfies the Axon Framework 5
 * minimum (Kotlin 2.0).
 * <p>
 * Wraps two upstream recipes to cover the two places a Kotlin version is pinned in a typical Maven build:
 * <ul>
 *   <li>{@link UpgradeDependencyVersion} for {@code org.jetbrains.kotlin:*} — bumps {@code kotlin-stdlib},
 *       {@code kotlin-reflect}, {@code kotlin-stdlib-jdk8}, and any other Kotlin runtime artifacts the
 *       project declares (Maven and Gradle); and</li>
 *   <li>{@link UpgradePluginVersion} for {@code org.jetbrains.kotlin:kotlin-maven-plugin} — bumps the
 *       Kotlin compiler plugin so Maven builds compile against the new language line.</li>
 * </ul>
 * <p>
 * The default target is {@value #DEFAULT_TARGET_VERSION} (Node-Semver "latest 2.x"), chosen so projects
 * already on a higher Kotlin 2.x release are left untouched — {@link UpgradeDependencyVersion} only
 * upgrades, never downgrades.
 * <p>
 * The {@link #targetVersion} option is overridable per invocation; values whose major version is below
 * {@value #MINIMUM_MAJOR_VERSION} are rejected so misconfigurations fail loudly rather than silently
 * pinning the build to a Kotlin 1.x line that Axon Framework 5 does not support.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class UpgradeKotlin extends Recipe {

    /**
     * Default Node-Semver selector used when {@link #targetVersion} is unset. {@code "2.x"} resolves to
     * the latest published Kotlin 2.x release, so projects already on a higher 2.x line are left
     * untouched (the underlying upgrade recipes never downgrade).
     */
    public static final String DEFAULT_TARGET_VERSION = "2.x";

    /**
     * Minimum supported major version. Axon Framework 5 requires Kotlin 2.x or higher; targets whose
     * major component is below this are rejected at configuration time.
     */
    public static final int MINIMUM_MAJOR_VERSION = 2;

    @Option(displayName = "Target Kotlin version",
            description = "Kotlin version (or Node-Semver selector) to upgrade to. Must resolve to Kotlin 2.0 "
                    + "or higher (Axon Framework 5 requires Kotlin 2.x+). Defaults to \"" + DEFAULT_TARGET_VERSION
                    + "\" so projects already on a higher 2.x release are left untouched. Updates the "
                    + "`org.jetbrains.kotlin:*` dependencies and the `kotlin-maven-plugin` version; does not "
                    + "apply Kotlin source-level rewrites.",
            example = "2.x",
            required = false)
    private final @Nullable String targetVersion;

    public UpgradeKotlin() {
        this(null);
    }

    @JsonCreator
    public UpgradeKotlin(@JsonProperty("targetVersion") @Nullable String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public @Nullable String getTargetVersion() {
        return targetVersion;
    }

    @Override
    public String getDisplayName() {
        return "Upgrade Kotlin to 2.x for Axon Framework 5";
    }

    @Override
    public String getDescription() {
        return "Bumps the `org.jetbrains.kotlin:*` dependency versions and the `kotlin-maven-plugin` to the "
                + "configured Kotlin line (defaults to \"" + DEFAULT_TARGET_VERSION + "\", the latest Kotlin 2.x). "
                + "No-op for modules already at or above the target — the underlying upgrade recipes never "
                + "downgrade. Rejects targets below Kotlin " + MINIMUM_MAJOR_VERSION + ".0.";
    }

    @Override
    public List<Recipe> getRecipeList() {
        String target = targetVersion != null ? targetVersion : DEFAULT_TARGET_VERSION;
        if (majorVersion(target) < MINIMUM_MAJOR_VERSION) {
            throw new IllegalArgumentException(
                    "Axon Framework 5 requires Kotlin " + MINIMUM_MAJOR_VERSION + ".0 or higher, "
                            + "but targetVersion was \"" + target + "\"");
        }
        return List.of(
                new UpgradeDependencyVersion("org.jetbrains.kotlin", "*", target, null, true, null),
                new UpgradePluginVersion("org.jetbrains.kotlin", "kotlin-maven-plugin", target, null, null, null)
        );
    }

    /**
     * Extracts the leading integer (the major version) from a Kotlin version selector. Accepts plain
     * versions ({@code "2.1.0"}), Node-Semver wildcards ({@code "2.x"}, {@code "2.1.x"}), and ranges
     * pinned to a leading major ({@code "2.1.0-Beta1"}). Selectors without a numeric prefix
     * (e.g. {@code "latest.release"}) are treated as satisfying the minimum since the resolver picks the
     * actual version at runtime.
     */
    private static int majorVersion(String selector) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(digits.toString());
    }
}
