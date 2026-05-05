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
import org.openrewrite.java.migrate.UpgradeJavaVersion;

import java.util.List;

/**
 * Bumps the Java compiler target to a configurable LTS, with validation that the target satisfies
 * the Axon Framework 5 minimum (Java 21).
 * <p>
 * Wraps {@link UpgradeJavaVersion} to:
 * <ul>
 *   <li>expose a single, well-named entrypoint with a sensible default
 *       ({@value #DEFAULT_TARGET_VERSION}, the current latest LTS);</li>
 *   <li>reject targets below {@value #MINIMUM_TARGET_VERSION} so misconfigurations fail loudly
 *       rather than producing a build that cannot compile against Axon 5.</li>
 * </ul>
 * <p>
 * This recipe only updates build files ({@code pom.xml}, {@code build.gradle}); it does not apply
 * any source-level Java modernizations. If you also want source rewrites such as text blocks,
 * pattern matching, sequenced collections or {@code javax}→{@code jakarta} translation, run the
 * upstream {@code org.openrewrite.java.migrate.UpgradeToJava21} separately or compose it into your
 * own wrapping recipe.
 * <p>
 * The {@link #targetVersion} option is overridable per invocation — see the module {@code README.md}
 * for instructions on switching between Java 25 (default) and Java 21.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class UpgradeJava extends Recipe {

    /**
     * Default target Java version when {@link #targetVersion} is unset. Equal to the latest released
     * LTS (Java 25, released September 2025).
     */
    public static final int DEFAULT_TARGET_VERSION = 25;

    /**
     * Minimum supported target. Axon Framework 5 requires Java 21+; lower targets are rejected at
     * configuration time.
     */
    public static final int MINIMUM_TARGET_VERSION = 21;

    @Option(displayName = "Target Java version",
            description = "Java LTS to target. Must be 21 or higher (Axon Framework 5 requires Java 21+). "
                    + "Defaults to " + DEFAULT_TARGET_VERSION + " (latest LTS). "
                    + "Updates the compiler target in build files only; source-level modernizations are "
                    + "handled by the upstream UpgradeToJava21 recipe composed in axon-4to5.yml.",
            example = "25",
            required = false)
    private final @Nullable Integer targetVersion;

    public UpgradeJava() {
        this(null);
    }

    @JsonCreator
    public UpgradeJava(@JsonProperty("targetVersion") @Nullable Integer targetVersion) {
        this.targetVersion = targetVersion;
    }

    public @Nullable Integer getTargetVersion() {
        return targetVersion;
    }

    @Override
    public String getDisplayName() {
        return "Upgrade Java compiler target for Axon Framework 5";
    }

    @Override
    public String getDescription() {
        return "Bumps the Java compiler target in pom.xml/build.gradle to the configured LTS "
                + "(defaults to " + DEFAULT_TARGET_VERSION + "). No-op for modules already at or above the target.";
    }

    @Override
    public List<Recipe> getRecipeList() {
        int target = targetVersion != null ? targetVersion : DEFAULT_TARGET_VERSION;
        if (target < MINIMUM_TARGET_VERSION) {
            throw new IllegalArgumentException(
                    "Axon Framework 5 requires Java " + MINIMUM_TARGET_VERSION + " or higher, "
                            + "but targetVersion was " + target);
        }
        return List.of(new UpgradeJavaVersion(target));
    }
}
