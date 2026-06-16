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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies the {@link UpgradeKotlin} composite recipe: the {@code targetVersion} option drives the
 * Kotlin runtime + plugin bump, defaults to {@link UpgradeKotlin#DEFAULT_TARGET_VERSION}, and is
 * rejected when the major version is below {@link UpgradeKotlin#MINIMUM_MAJOR_VERSION}.
 */
class UpgradeKotlinTest implements RewriteTest {

    @Nested
    class TargetVersionDefault {

        @Test
        void usesDefaultLatest2xWhenOptionOmitted() {
            // given / when / then
            UpgradeKotlin recipe = new UpgradeKotlin();
            assertThat(recipe.getTargetVersion()).isNull();
            // two upstream recipes: dependency upgrade + maven plugin upgrade
            assertThat(recipe.getRecipeList()).hasSize(2);
        }
    }

    @Nested
    class TargetVersionExplicit {

        @Test
        void bumpsKotlinStdlibDependencyToRequestedVersion() {
            // given / when / then
            rewriteRun(
                    spec -> spec.recipe(new UpgradeKotlin("2.1.0")),
                    pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.jetbrains.kotlin</groupId>
                                        <artifactId>kotlin-stdlib</artifactId>
                                        <version>1.9.25</version>
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
                                        <groupId>org.jetbrains.kotlin</groupId>
                                        <artifactId>kotlin-stdlib</artifactId>
                                        <version>2.1.0</version>
                                    </dependency>
                                </dependencies>
                            </project>
                            """
                    )
            );
        }

        @Test
        void bumpsKotlinMavenPluginToRequestedVersion() {
            // given / when / then
            rewriteRun(
                    spec -> spec.recipe(new UpgradeKotlin("2.1.0")),
                    pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <build>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.jetbrains.kotlin</groupId>
                                            <artifactId>kotlin-maven-plugin</artifactId>
                                            <version>1.9.25</version>
                                        </plugin>
                                    </plugins>
                                </build>
                            </project>
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <build>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.jetbrains.kotlin</groupId>
                                            <artifactId>kotlin-maven-plugin</artifactId>
                                            <version>2.1.0</version>
                                        </plugin>
                                    </plugins>
                                </build>
                            </project>
                            """
                    )
            );
        }

        @Test
        void leavesPomUntouchedWhenAlreadyAboveTarget() {
            // given / when / then — already on a higher 2.x release; underlying recipe never downgrades.
            rewriteRun(
                    spec -> spec.recipe(new UpgradeKotlin("2.0.0")),
                    pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.jetbrains.kotlin</groupId>
                                        <artifactId>kotlin-stdlib</artifactId>
                                        <version>2.1.0</version>
                                    </dependency>
                                </dependencies>
                            </project>
                            """
                    )
            );
        }
    }

    @Nested
    class TargetVersionValidation {

        @Test
        void rejectsKotlin1xTarget() {
            // given
            UpgradeKotlin recipe = new UpgradeKotlin("1.9.25");

            // when / then
            assertThatThrownBy(recipe::getRecipeList)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires Kotlin 2.0");
        }
    }

    @Override
    public void defaults(RecipeSpec spec) {
        // each test sets its own recipe via spec.recipe(...)
    }
}
