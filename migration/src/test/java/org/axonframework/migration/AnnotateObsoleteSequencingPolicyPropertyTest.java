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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

/**
 * Verifies the advisory comment insertion for obsolete
 * {@code axon.eventhandling.processors.<group>.sequencing-policy} properties.
 */
class AnnotateObsoleteSequencingPolicyPropertyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AnnotateObsoleteSequencingPolicyProperty());
    }

    @Test
    void insertsCommentAboveSequencingPolicyInPropertiesFile() {
        rewriteRun(
                properties(
                        """
                        axon.eventhandling.processors.bikes.sequencing-policy=full
                        """,
                        """
                        # TODO(axon4to5): move sequencing-policy to @SequencingPolicy on the handler class; this property has no AF5 equivalent.
                        axon.eventhandling.processors.bikes.sequencing-policy=full
                        """,
                        spec -> spec.path("application.properties")
                )
        );
    }

    @Test
    void leavesUnrelatedPropertiesUntouched() {
        rewriteRun(
                properties(
                        """
                        axon.eventhandling.processors.bikes.mode=tracking
                        spring.application.name=demo
                        """,
                        spec -> spec.path("application.properties")
                )
        );
    }

    @Test
    void isIdempotentOnPropertiesFiles() {
        // Already-annotated input must be left as-is on a re-run.
        rewriteRun(
                properties(
                        """
                        # TODO(axon4to5): move sequencing-policy to @SequencingPolicy on the handler class; this property has no AF5 equivalent.
                        axon.eventhandling.processors.bikes.sequencing-policy=full
                        """,
                        spec -> spec.path("application.properties")
                )
        );
    }

    @Test
    void insertsCommentAboveSequencingPolicyInYamlFile() {
        rewriteRun(
                yaml(
                        """
                        axon:
                          eventhandling:
                            processors:
                              bikes:
                                sequencing-policy: full
                        """,
                        """
                        axon:
                          eventhandling:
                            processors:
                              bikes:
                                # TODO(axon4to5): move sequencing-policy to @SequencingPolicy on the handler class; this property has no AF5 equivalent.
                                sequencing-policy: full
                        """,
                        spec -> spec.path("application.yml")
                )
        );
    }

    @Test
    void leavesUnrelatedYamlEntriesUntouched() {
        // `sequencing-policy` under a different parent path must not be touched.
        rewriteRun(
                yaml(
                        """
                        spring:
                          application:
                            name: demo
                        unrelated:
                          sequencing-policy: full
                        """,
                        spec -> spec.path("application.yml")
                )
        );
    }

    @Test
    void isIdempotentOnYamlFiles() {
        rewriteRun(
                yaml(
                        """
                        axon:
                          eventhandling:
                            processors:
                              bikes:
                                # TODO(axon4to5): move sequencing-policy to @SequencingPolicy on the handler class; this property has no AF5 equivalent.
                                sequencing-policy: full
                        """,
                        spec -> spec.path("application.yml")
                )
        );
    }
}
