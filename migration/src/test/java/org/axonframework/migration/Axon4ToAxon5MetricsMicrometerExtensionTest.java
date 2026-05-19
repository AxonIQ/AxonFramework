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
 * Verifies the Micrometer extension migration: package move from
 * {@code org.axonframework.micrometer} to
 * {@code org.axonframework.extension.metrics.micrometer}.
 */
class Axon4ToAxon5MetricsMicrometerExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5MetricsMicrometerExtension"));
    }

    @Test
    void renamesMicrometerExtensionPackage() {
        // ChangePackage preserves the original whitespace (no extra blank lines added).
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.micrometer.MessageCountingMonitor;
                        class Foo {
                            MessageCountingMonitor monitor;
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.extension.metrics.micrometer.MessageCountingMonitor;
                        class Foo {
                            MessageCountingMonitor monitor;
                        }
                        """
                )
        );
    }
}
