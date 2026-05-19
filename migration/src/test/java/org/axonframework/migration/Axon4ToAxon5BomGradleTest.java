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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.gradle.marker.GradleBuildscript;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.UncheckedConsumer;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

/**
 * Gradle counterpart to {@link Axon4ToAxon5BomTest}. Verifies the BOM swap and
 * version pin across the Gradle build-script flavors users have in the wild:
 * <ul>
 *     <li>Groovy and Kotlin DSL build scripts</li>
 *     <li>Plain {@code platform(...)} dependencies and Spring Dependency
 *         Management plugin {@code mavenBom(...)} imports</li>
 *     <li>Inline literal versions and versions read via {@code property(...)} /
 *         {@code extra[...]} indirection (the Cinema demo flavor)</li>
 * </ul>
 *
 * <strong>Skipped on CI</strong> for the same reason as
 * {@link Axon4ToAxon5BomTest} — the renamed BOM coordinates aren't resolvable
 * on a fresh CI runner. Run locally after
 * {@code ./mvnw -pl axon-framework-bom -am install -DskipTests}.
 */
@DisabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason = "Target BOMs (axon-framework-bom and axoniq-framework-bom "
                + "at the current SNAPSHOT) are not resolvable on a fresh CI runner; "
                + "see Axon4ToAxon5BomTest's class Javadoc for the full explanation."
)
class Axon4ToAxon5BomGradleTest implements RewriteTest {

    private static final String AXON_VERSION = loadVersion("axon.version");
    private static final String AXONIQ_VERSION = loadVersion("axoniq.version");

    private static String loadVersion(String key) {
        Properties properties = new Properties();
        try (InputStream in = Axon4ToAxon5BomGradleTest.class.getResourceAsStream("/migration-versions.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "migration-versions.properties is missing from the test classpath — "
                                + "the migration POM must filter it under <testResources>.");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read migration-versions.properties", e);
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing or blank `" + key + "` in migration-versions.properties");
        }
        return value;
    }

    @Override
    public void defaults(RecipeSpec spec) {
        // each test activates its composite via spec.recipe(...)
    }

    private static RecipeSpec freeLeg(RecipeSpec spec) {
        return spec.beforeRecipe(attachSyntheticGradleProject())
                   .recipe(Environment.builder()
                                   .scanRuntimeClasspath("org.axonframework.migration")
                                   .build()
                                   .activateRecipes("org.axonframework.migration.Axon4ToAxon5Bom"));
    }

    private static RecipeSpec commercialLeg(RecipeSpec spec) {
        return spec.beforeRecipe(attachSyntheticGradleProject())
                   .recipe(Environment.builder()
                                   .scanRuntimeClasspath("io.axoniq.framework.migration")
                                   .build()
                                   .activateRecipes("io.axoniq.framework.migration.Axon4ToAxoniq5Bom"));
    }

    /**
     * Attach a minimal {@link GradleProject} marker to every {@code build.gradle}
     * and {@code build.gradle.kts} source. The Gradle-aware OpenRewrite recipes
     * (notably {@code gradle.ChangeDependency} and
     * {@code gradle.ChangeManagedDependency}) are gated on
     * {@code FindGradleProject} and silently no-op without the marker.
     * <p>
     * Production builds get this marker from the OpenRewrite Gradle plugin via
     * {@code withToolingApi()}, which spins up a real Gradle daemon to compute
     * the project model. That's overkill (and pulls in {@code gradle-tooling-api}
     * from a non-Maven-Central repo) for unit tests that only verify textual
     * rewrites — a synthetic, dependency-free marker is enough to satisfy the
     * recipes' precondition.
     */
    private static UncheckedConsumer<List<SourceFile>> attachSyntheticGradleProject() {
        return sourceFiles -> {
            // Skip the deprecated `mavenPluginRepositories` — the builder
            // defaults it to `emptyList()` already, and setting it explicitly
            // pulls in a deprecation warning.
            GradleProject syntheticMarker = GradleProject.builder()
                    .id(randomId())
                    .group("")
                    .name("")
                    .version("")
                    .path("")
                    .plugins(emptyList())
                    .mavenRepositories(emptyList())
                    .nameToConfiguration(emptyMap())
                    .buildscript(new GradleBuildscript(randomId(), emptyList(), emptyMap()))
                    .build();
            for (int i = 0; i < sourceFiles.size(); i++) {
                SourceFile sourceFile = sourceFiles.get(i);
                boolean isGradleScript = sourceFile instanceof G.CompilationUnit
                        && sourceFile.getSourcePath().toString().endsWith(".gradle");
                boolean isKotlinGradleScript = sourceFile instanceof K.CompilationUnit
                        && sourceFile.getSourcePath().toString().endsWith(".gradle.kts");
                if (isGradleScript || isKotlinGradleScript) {
                    sourceFiles.set(i, sourceFile.withMarkers(
                            sourceFile.getMarkers().setByType(syntheticMarker)));
                }
            }
        };
    }

    @Nested
    class FreeLegGroovy {

        @Test
        void platformDependencyWithLiteralVersion() {
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::freeLeg,
                    buildGradle(
                            """
                            plugins { id 'java' }
                            repositories { mavenCentral() }
                            dependencies {
                                implementation platform('org.axonframework:axon-bom:4.13.0')
                            }
                            """,
                            """
                            plugins { id 'java' }
                            repositories { mavenCentral() }
                            dependencies {
                                implementation platform('org.axonframework:axon-framework-bom:%s')
                            }
                            """.formatted(AXON_VERSION)
                    )
            );
        }

        @Test
        void springDependencyManagementMavenBomLiteralVersion() {
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::freeLeg,
                    buildGradle(
                            """
                            plugins {
                                id 'java'
                                id 'io.spring.dependency-management' version '1.1.7'
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom 'org.axonframework:axon-bom:4.13.0'
                                }
                            }
                            """,
                            """
                            plugins {
                                id 'java'
                                id 'io.spring.dependency-management' version '1.1.7'
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom 'org.axonframework:axon-framework-bom:%s'
                                }
                            }
                            """.formatted(AXON_VERSION)
                    )
            );
        }
    }

    @Nested
    class FreeLegKotlinDsl {

        // NOTE: There's no Kotlin-DSL `implementation(platform("g:a:v"))`
        // counterpart to `FreeLegGroovy.platformDependencyWithLiteralVersion`
        // here. The stock `gradle.ChangeDependency` recipe doesn't reliably
        // visit Kotlin-DSL `platform(...)` calls in unit tests (the synthetic
        // `GradleProject` marker we attach lacks the resolved configurations
        // its scanner inspects, and unlike Spring DM `mavenBom(...)` there is
        // no Kotlin-aware fallback trait we can compose). Cinema-style
        // projects pin the BOM via Spring DM, which is fully covered below;
        // the platform-call gap is tracked for an upstream fix.

        @Test
        void springDependencyManagementMavenBomLiteralVersion() {
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::freeLeg,
                    buildGradleKts(
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom("org.axonframework:axon-bom:4.13.0")
                                }
                            }
                            """,
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom("org.axonframework:axon-framework-bom:%s")
                                }
                            }
                            """.formatted(AXON_VERSION)
                    )
            );
        }

        @Test
        void springDependencyManagementMavenBomVersionFromExtraProperty() {
            // Cinema-style fixture: version pinned via `extra["axonFrameworkVersion"]`
            // and read by `${property("axonFrameworkVersion")}`. The trait is
            // smart enough to keep the `${property(...)}` indirection — the
            // recipe rewrites the BOM artifactId in place; the literal version
            // stored in `extra[...]` is updated by a follow-up
            // `org.axonframework.migration.UpgradeAxonFrameworkExtraVersion`
            // composed into the same recipe.
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::freeLeg,
                    buildGradleKts(
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            extra["axonFrameworkVersion"] = "4.12.1"
                            dependencyManagement {
                                imports {
                                    mavenBom("org.axonframework:axon-bom:${property("axonFrameworkVersion")}")
                                }
                            }
                            """,
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            extra["axonFrameworkVersion"] = "%s"
                            dependencyManagement {
                                imports {
                                    mavenBom("org.axonframework:axon-framework-bom:${property("axonFrameworkVersion")}")
                                }
                            }
                            """.formatted(AXON_VERSION)
                    )
            );
        }
    }

    @Nested
    class CommercialLegGroovy {

        @Test
        void springDependencyManagementMavenBomFromAxon4() {
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::commercialLeg,
                    buildGradle(
                            """
                            plugins {
                                id 'java'
                                id 'io.spring.dependency-management' version '1.1.7'
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom 'org.axonframework:axon-bom:4.13.0'
                                }
                            }
                            """,
                            """
                            plugins {
                                id 'java'
                                id 'io.spring.dependency-management' version '1.1.7'
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom 'io.axoniq.framework:axoniq-framework-bom:%s'
                                }
                            }
                            """.formatted(AXONIQ_VERSION)
                    )
            );
        }
    }

    @Nested
    class CommercialLegKotlinDsl {

        @Test
        void springDependencyManagementMavenBomFromAxon4WithExtraProperty() {
            // Cinema flavor through the commercial leg.
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::commercialLeg,
                    buildGradleKts(
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            extra["axonFrameworkVersion"] = "4.12.1"
                            dependencyManagement {
                                imports {
                                    mavenBom("org.axonframework:axon-bom:${property("axonFrameworkVersion")}")
                                }
                            }
                            """,
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            extra["axonFrameworkVersion"] = "%s"
                            dependencyManagement {
                                imports {
                                    mavenBom("io.axoniq.framework:axoniq-framework-bom:${property("axonFrameworkVersion")}")
                                }
                            }
                            """.formatted(AXONIQ_VERSION)
                    )
            );
        }

        @Test
        void springDependencyManagementMavenBomFromFreeAxon5() {
            // Order-independence: when the free leg already swapped to
            // `axon-framework-bom`, the commercial leg must still take it
            // over to the Axoniq commercial coordinate.
            rewriteRun(
                    Axon4ToAxon5BomGradleTest::commercialLeg,
                    buildGradleKts(
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom("org.axonframework:axon-framework-bom:%s")
                                }
                            }
                            """.formatted(AXON_VERSION),
                            """
                            plugins {
                                java
                                id("io.spring.dependency-management") version "1.1.7"
                            }
                            repositories { mavenCentral() }
                            dependencyManagement {
                                imports {
                                    mavenBom("io.axoniq.framework:axoniq-framework-bom:%s")
                                }
                            }
                            """.formatted(AXONIQ_VERSION)
                    )
            );
        }
    }
}
