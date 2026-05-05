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

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the common-module migration: package move from
 * {@code org.axonframework.config} to {@code org.axonframework.common.configuration}
 * and the {@code ConfigurerModule} → {@code ConfigurationEnhancer} rename.
 */
class Axon4ToAxon5CommonTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5Common"));
    }

    @Test
    void renamesConfigurerModuleToConfigurationEnhancer() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.config.ConfigurerModule;
                        class Foo implements ConfigurerModule {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.common.configuration.ConfigurationEnhancer;

                        class Foo implements ConfigurationEnhancer {}
                        """
                )
        );
    }

    @Test
    void renamesProcessingGroupToNamespacePreservingAttributeValue() {
        // AF4 `@ProcessingGroup("...")` → AF5 `@Namespace("...")`. Both
        // annotations carry a single `String value()`, so `ChangeType`
        // preserves the argument verbatim. The processor name string the
        // user picked stays as-is — operators may want to revisit naming
        // since AF5's namespace concept is broader than AF4's processing
        // group, but that's a manual decision.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.config.ProcessingGroup;
                        @ProcessingGroup("automation_processor")
                        class Projection {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.core.annotation.Namespace;

                        @Namespace("automation_processor")
                        class Projection {}
                        """
                )
        );
    }
}
