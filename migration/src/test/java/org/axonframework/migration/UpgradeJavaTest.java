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
 * Verifies the {@link UpgradeJava} composite recipe: the {@code targetVersion} option drives the
 * compiler-target bump in build files, defaults to {@link UpgradeJava#DEFAULT_TARGET_VERSION}, and
 * is rejected when below {@link UpgradeJava#MINIMUM_TARGET_VERSION}.
 */
class UpgradeJavaTest implements RewriteTest {

    @Nested
    class TargetVersionDefault {

        @Test
        void usesDefaultLtsWhenOptionOmitted() {
            // given / when / then
            UpgradeJava recipe = new UpgradeJava();
            assertThat(recipe.getTargetVersion()).isNull();
            assertThat(recipe.getRecipeList()).hasSize(1);
        }
    }

    @Nested
    class TargetVersionExplicit {

        @Test
        void bumpsMavenCompilerReleaseToRequestedVersion() {
            // given / when / then
            rewriteRun(
                    spec -> spec.recipe(new UpgradeJava(25)),
                    pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <properties>
                                    <maven.compiler.source>17</maven.compiler.source>
                                    <maven.compiler.target>17</maven.compiler.target>
                                </properties>
                            </project>
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <properties>
                                    <maven.compiler.source>25</maven.compiler.source>
                                    <maven.compiler.target>25</maven.compiler.target>
                                </properties>
                            </project>
                            """
                    )
            );
        }

        @Test
        void leavesPomUntouchedWhenAlreadyAtTarget() {
            // given / when / then
            rewriteRun(
                    spec -> spec.recipe(new UpgradeJava(21)),
                    pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>demo</artifactId>
                                <version>1.0</version>
                                <properties>
                                    <maven.compiler.source>21</maven.compiler.source>
                                    <maven.compiler.target>21</maven.compiler.target>
                                </properties>
                            </project>
                            """
                    )
            );
        }
    }

    @Nested
    class TargetVersionValidation {

        @Test
        void rejectsTargetBelowMinimum() {
            // given
            UpgradeJava recipe = new UpgradeJava(17);

            // when / then
            assertThatThrownBy(recipe::getRecipeList)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires Java 21");
        }
    }

    @Override
    public void defaults(RecipeSpec spec) {
        // each test sets its own recipe via spec.recipe(...)
    }
}
