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

import static org.openrewrite.java.Assertions.srcMainResources;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

/**
 * Verifies the Spring Boot extension migration covers Spring configuration property
 * key renames. Specifically, AF4's {@code @ConfigurationProperties("axon.serializer")}
 * (SerializerProperties) is replaced in AF5 by {@code @ConfigurationProperties("axon.converter")}
 * (ConverterProperties), so {@code axon.serializer.{general,messages,events}} must be
 * rewritten to {@code axon.converter.{general,messages,events}} in both
 * {@code application.properties} and {@code application.yml} / {@code application.yaml}.
 */
class Axon4ToAxon5SpringBootExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5SpringBootExtension"));
    }

    @Test
    void renamesSerializerPropertyKeysInApplicationProperties() {
        rewriteRun(
                srcMainResources(
                        properties(
                                """
                                axon.serializer.general=jackson
                                axon.serializer.messages=jackson
                                axon.serializer.events=jackson
                                """,
                                """
                                axon.converter.general=jackson
                                axon.converter.messages=jackson
                                axon.converter.events=jackson
                                """,
                                spec -> spec.path("application.properties")
                        )
                )
        );
    }

    @Test
    void renamesSerializerPropertyKeysInApplicationYaml() {
        // The recipe re-nests the renamed branch via `UnfoldProperties` so the
        // output preserves the conventional nested YAML shape rather than the
        // half-flat form that the underlying `ChangeSpringPropertyKey` produces.
        rewriteRun(
                srcMainResources(
                        yaml(
                                """
                                axon:
                                  serializer:
                                    general: jackson
                                    messages: jackson
                                    events: jackson
                                """,
                                """
                                axon:
                                  converter:
                                    general: jackson
                                    messages: jackson
                                    events: jackson
                                """,
                                spec -> spec.path("application.yml")
                        )
                )
        );
    }

    @Test
    void leavesUnrelatedAxonPropertiesUntouched() {
        // Properties under other AF4 prefixes (e.g. `axon.eventhandling.*`) are not
        // affected by the serializer → converter rename and must pass through unchanged.
        rewriteRun(
                srcMainResources(
                        properties(
                                """
                                axon.eventhandling.processors.my-processor.mode=tracking
                                """,
                                spec -> spec.path("application.properties")
                        )
                )
        );
    }
}
