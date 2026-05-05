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
 * Verifies the test-module fixture migration: {@code AggregateTestFixture}
 * and {@code SagaTestFixture} both rename to the unified
 * {@code AxonTestFixture}.
 */
class Axon4ToAxon5TestTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5Test"));
    }

    @Test
    void renamesAggregateTestFixtureToAxonTestFixture() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture<Object> fixture;
                        }
                        """
                )
        );
    }
}
