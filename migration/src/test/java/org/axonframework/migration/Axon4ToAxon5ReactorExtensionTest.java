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

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies the Reactor extension migration covers the deprecated AF4
 * {@code axon-reactor-spring-boot-starter} coordinate. AF5 ships only the bare
 * {@code axon-reactor} module, so the recipe rewrites the dependency in
 * {@code pom.xml}, {@code build.gradle}, and {@code build.gradle.kts} via the
 * unified {@code java.dependencies.ChangeDependency} recipe. Reactive Spring
 * Boot autoconfig must be wired manually in AF5 — this is intentional.
 * <p>
 * Only the Maven leg is asserted here. The Gradle leg of
 * {@code java.dependencies.ChangeDependency} requires a {@code GradleProject}
 * marker attached by the Gradle Tooling API during test setup, which the
 * other extension renames in this repo (Spring, Micrometer, OpenTelemetry)
 * also do not exercise; they trust the unified recipe's upstream Gradle
 * coverage. Package-move tests are not duplicated here either; the
 * {@code ChangePackage} steps in the same recipe are exercised by the
 * broader extension tests.
 */
class Axon4ToAxon5ReactorExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5ReactorExtension"));
    }

    @Test
    void renamesStarterToBareModuleInMavenPom() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencies>
                                <dependency>
                                    <groupId>org.axonframework.extensions.reactor</groupId>
                                    <artifactId>axon-reactor-spring-boot-starter</artifactId>
                                    <version>4.11.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencies>
                                <dependency>
                                    <groupId>org.axonframework.extensions.reactor</groupId>
                                    <artifactId>axon-reactor</artifactId>
                                    <version>4.11.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """
                )
        );
    }

    @Test
    void leavesProjectsAlreadyOnAxonReactorUntouched() {
        // A project that depends only on `axon-reactor` (no starter) doesn't need any rewrite.
        // This catches accidental matches if the recipe's selector were widened.
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencies>
                                <dependency>
                                    <groupId>org.axonframework.extensions.reactor</groupId>
                                    <artifactId>axon-reactor</artifactId>
                                    <version>4.11.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """
                )
        );
    }
}
