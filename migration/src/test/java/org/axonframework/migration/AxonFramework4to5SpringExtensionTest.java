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
 * Verifies the Spring extension migration: package move from
 * {@code org.axonframework.spring} to {@code org.axonframework.extension.spring}
 * plus the {@code @Aggregate} → {@code @EventSourced} stereotype rename.
 */
class AxonFramework4to5SpringExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.AxonFramework4to5SpringExtension"));
    }

    @Test
    void renamesAggregateStereotypeToEventSourced() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.spring.stereotype.Aggregate;
                        @Aggregate
                        class Order {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.extension.spring.stereotype.EventSourced;

                        @EventSourced
                        class Order {}
                        """
                )
        );
    }
}
