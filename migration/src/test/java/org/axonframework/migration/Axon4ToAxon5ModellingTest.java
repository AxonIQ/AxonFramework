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
 * Verifies the modelling-module migration (aggregates → entities):
 * {@code modelling.command} relocates to {@code modelling.entity}, and
 * {@code @TargetAggregateIdentifier} renames to {@code @TargetEntityId}.
 */
class Axon4ToAxon5ModellingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5Modelling"));
    }

    @Test
    void renamesTargetAggregateIdentifierToTargetEntityId() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.modelling.command.TargetAggregateIdentifier;
                        class CreateOrder {
                            @TargetAggregateIdentifier
                            String orderId;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.modelling.annotation.TargetEntityId;

                        class CreateOrder {
                            @TargetEntityId
                            String orderId;
                        }
                        """
                )
        );
    }
}
