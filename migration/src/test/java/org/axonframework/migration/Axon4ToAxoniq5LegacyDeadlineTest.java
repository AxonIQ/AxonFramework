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
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static java.util.Objects.requireNonNull;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies migration of Axon Framework 4 deadlines onto the {@code io.axoniq.framework:axoniq-legacy} compatibility
 * module.
 */
class Axon4ToAxoniq5LegacyDeadlineTest implements RewriteTest {

    private static final String AXONIQ_VERSION = axoniqVersion();

    private static final String PLAIN_POM = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>rental</artifactId>
                <version>1.0.0</version>
            </project>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("io.axoniq.framework.migration.Axon4ToAxoniq5LegacyDeadline"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class DependencyMigration {

        @Test
        void addsAxoniqLegacyWhenDeadlineTypesAreUsed() {
            rewriteRun(
                    Axon4ToAxoniq5LegacyDeadlineTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                                    PLAIN_POM,
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                        <dependencies>
                                            <dependency>
                                                <groupId>io.axoniq.framework</groupId>
                                                <artifactId>axoniq-legacy</artifactId>
                                                <version>%s</version>
                                            </dependency>
                                        </dependencies>
                                    </project>
                                    """.formatted(AXONIQ_VERSION)
                            ),
                            srcMainJava(
                                    java(
                                            """
                                            package com.example;

                                            import org.axonframework.deadline.DeadlineManager;

                                            class RentalScheduler {
                                                void schedule(DeadlineManager deadlineManager) {
                                                    deadlineManager.cancelAll("rentalExpired");
                                                }
                                            }
                                            """
                                    )
                            )
                    )
            );
        }

        @Test
        void addsBothLegacyModulesInTestScopeWhenOnlyTestsUseStubDeadlineManager() {
            rewriteRun(
                    Axon4ToAxoniq5LegacyDeadlineTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                                    PLAIN_POM,
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                        <dependencies>
                                            <dependency>
                                                <groupId>io.axoniq.framework</groupId>
                                                <artifactId>axoniq-legacy</artifactId>
                                                <version>%s</version>
                                                <scope>test</scope>
                                            </dependency>
                                            <dependency>
                                                <groupId>io.axoniq.framework</groupId>
                                                <artifactId>axoniq-legacy-test</artifactId>
                                                <version>%s</version>
                                                <scope>test</scope>
                                            </dependency>
                                        </dependencies>
                                    </project>
                                    """.formatted(AXONIQ_VERSION, AXONIQ_VERSION)
                            ),
                            // StubDeadlineManager implements the legacy DeadlineManager, so the
                            // test-only usage pulls in axoniq-legacy in test scope as well.
                            srcTestJava(
                                    java(
                                            """
                                            package com.example;

                                            import org.axonframework.test.deadline.StubDeadlineManager;

                                            class RentalSchedulerTest {
                                                private final StubDeadlineManager deadlineManager = new StubDeadlineManager();
                                            }
                                            """
                                    )
                            )
                    )
            );
        }
    }

    private static void ignoreUnpublishedTargetVersionWarning(RecipeSpec spec) {
        spec.markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY);
    }

    private static String axoniqVersion() {
        Properties versions = new Properties();
        try (InputStream input = requireNonNull(
                Axon4ToAxoniq5LegacyDeadlineTest.class.getResourceAsStream("/migration-versions.properties")
        )) {
            versions.load(input);
            return versions.getProperty("axoniq.version");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read migration target versions", exception);
        }
    }
}
