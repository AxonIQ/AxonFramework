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

package io.axoniq.framework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the Axon Server connector migration: package move from the
 * open-source {@code org.axonframework.axonserver.connector} into the Axoniq
 * commercial {@code io.axoniq.framework.axonserver.connector}. Class names
 * remain identical.
 */
class Axon4ToAxoniq5AxonServerConnectorTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("io.axoniq.framework.migration")
                            .build()
                            .activateRecipes(
                                    "io.axoniq.framework.migration.Axon4ToAxoniq5AxonServerConnector"));
    }

    @Test
    void renamesAxonServerConnectionManagerToAxoniqCommercial() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.axonserver.connector.AxonServerConnectionManager;
                        class Foo {
                            AxonServerConnectionManager mgr;
                        }
                        """,
                        """
                        package com.example;
                        import io.axoniq.framework.axonserver.connector.AxonServerConnectionManager;
                        class Foo {
                            AxonServerConnectionManager mgr;
                        }
                        """
                )
        );
    }
}
