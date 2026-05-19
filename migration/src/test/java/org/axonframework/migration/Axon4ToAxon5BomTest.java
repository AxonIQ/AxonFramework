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
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies the BOM rename plus managed-version bump for both the free
 * ({@code axon-framework-bom}) and commercial ({@code axoniq-framework-bom})
 * upgrade compositions. The renamed BOM coordinate doesn't exist at the AF4
 * version line, so a follow-up {@code UpgradeDependencyVersion} pass is
 * unable to lift it; the rename recipe pins the version directly via the
 * {@code newVersion} option of
 * {@link org.openrewrite.maven.ChangeManagedDependencyGroupIdAndArtifactId}.
 * <p>
 * <strong>Skipped on CI.</strong> OpenRewrite's {@code MavenParser} performs
 * full POM resolution against the local Maven repository plus configured
 * remote repos. Both target BOMs in these tests are unresolvable on a fresh
 * CI runner:
 * <ul>
 *     <li>{@code org.axonframework:axon-framework-bom:${project.version}} is
 *         built by this reactor but {@code mvn verify} (the CI goal) does not
 *         install it to {@code ~/.m2/repository}, so resolution fails.</li>
 *     <li>{@code io.axoniq.framework:axoniq-framework-bom:${axoniq.version}}
 *         is published by a separate commercial repository and is not present
 *         in this build at all.</li>
 * </ul>
 * Failed resolution surfaces either as
 * {@code <!--~~(... failed ...)~~>--&gt;} markers in the rewritten POM
 * (breaking textual assertions) or as a {@code MavenDownloadingException}
 * when the test input itself references the unresolvable coordinate. Until
 * the migration build seeds a sandbox local repository with stub POMs (or CI
 * switches to {@code mvn install}), this class is disabled on CI via
 * {@link DisabledIfEnvironmentVariable}. Run it locally after
 * {@code ./mvnw -pl axon-framework-bom -am install -DskipTests} to verify
 * the recipes.
 */
@DisabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason = "Target BOMs (axon-framework-bom and axoniq-framework-bom "
                + "at the current SNAPSHOT) are not resolvable on a fresh CI runner; "
                + "see class Javadoc for the full explanation and the local repro command."
)
class Axon4ToAxon5BomTest implements RewriteTest {

    /**
     * AF5 free target version, loaded from {@code migration-versions.properties}
     * on the test classpath. The properties file is filtered at build time so
     * its {@code axon.version} entry mirrors the value injected into the
     * filtered recipe YAMLs (derived from the parent POM's {@code <revision>}).
     */
    private static final String AXON_VERSION = loadVersion("axon.version");

    /**
     * AF5 commercial (Axoniq) target version, loaded from the same filtered
     * {@code migration-versions.properties}. Mirrors the {@code <axoniq.version>}
     * property declared in the migration POM.
     */
    private static final String AXONIQ_VERSION = loadVersion("axoniq.version");

    private static String loadVersion(String key) {
        Properties properties = new Properties();
        try (InputStream in = Axon4ToAxon5BomTest.class.getResourceAsStream("/migration-versions.properties")) {
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

    @Test
    void freeLegRenamesBomAndBumpsManagedVersion() {
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.Axon4ToAxon5Bom")),
                pomXml(
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.axonframework</groupId>
                                        <artifactId>axon-bom</artifactId>
                                        <version>4.13.0</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """,
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.axonframework</groupId>
                                        <artifactId>axon-framework-bom</artifactId>
                                        <version>%s</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """.formatted(AXON_VERSION)
                )
        );
    }

    @Test
    void commercialLegRenamesAxon4BomAndBumpsManagedVersion() {
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("io.axoniq.framework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "io.axoniq.framework.migration.Axon4ToAxoniq5Bom")),
                pomXml(
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.axonframework</groupId>
                                        <artifactId>axon-bom</artifactId>
                                        <version>4.13.0</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """,
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.axoniq.framework</groupId>
                                        <artifactId>axoniq-framework-bom</artifactId>
                                        <version>%s</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """.formatted(AXONIQ_VERSION)
                )
        );
    }

    @Test
    void commercialLegSwapsFreeAxon5BomToCommercialAndBumpsManagedVersion() {
        // Order-independence: when Axon4ToAxon5Bom has already run, the BOM is
        // org.axonframework:axon-framework-bom at the AF5 free version. Axon4ToAxoniq5Bom
        // must still take it to io.axoniq.framework:axoniq-framework-bom at the Axoniq version.
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("io.axoniq.framework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "io.axoniq.framework.migration.Axon4ToAxoniq5Bom")),
                pomXml(
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.axonframework</groupId>
                                        <artifactId>axon-framework-bom</artifactId>
                                        <version>%s</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """.formatted(AXON_VERSION),
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.axoniq.framework</groupId>
                                        <artifactId>axoniq-framework-bom</artifactId>
                                        <version>%s</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """.formatted(AXONIQ_VERSION)
                )
        );
    }

    @Test
    void freeLegIsNoOpWhenAlreadyAtTargetCoordinatesAndVersion() {
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.Axon4ToAxon5Bom")),
                pomXml(
                        """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>demo</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.axonframework</groupId>
                                        <artifactId>axon-framework-bom</artifactId>
                                        <version>%s</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """.formatted(AXON_VERSION)
                )
        );
    }

    @Override
    public void defaults(RecipeSpec spec) {
        // each test activates its composite via spec.recipe(...)
    }
}
