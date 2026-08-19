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
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link AddAxonTestFixtureTearDown}: adds an
 * {@code @AfterEach tearDown()} calling {@code fixture.stop()} when (and only
 * when) the test class declares an {@code AxonTestFixture} field and has no
 * existing {@code @AfterEach} or method named {@code tearDown}.
 */
class AddAxonTestFixtureTearDownTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddAxonTestFixtureTearDown())
                // The AF5 AxonTestFixture isn't on the test classpath; without disabling
                // type validation OpenRewrite would reject the post-rewrite tree. The
                // recipe matches by FQN string, not by resolved JavaType, so this is safe.
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void addsTearDownWhenAxonTestFixtureFieldExistsAndNoAfterEach() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        import org.junit.jupiter.api.BeforeEach;
                        class FooTest {
                            AxonTestFixture fixture;
                            @BeforeEach
                            void setUp() {
                                fixture = AxonTestFixture.with(null);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        import org.junit.jupiter.api.AfterEach;
                        import org.junit.jupiter.api.BeforeEach;

                        class FooTest {
                            AxonTestFixture fixture;
                            @BeforeEach
                            void setUp() {
                                fixture = AxonTestFixture.with(null);
                            }

                            @AfterEach
                            void tearDown() {
                                fixture.stop();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void preservesNonStandardFieldName() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        import org.junit.jupiter.api.BeforeEach;
                        class FooTest {
                            AxonTestFixture testFixture;
                            @BeforeEach
                            void setUp() {
                                testFixture = AxonTestFixture.with(null);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        import org.junit.jupiter.api.AfterEach;
                        import org.junit.jupiter.api.BeforeEach;

                        class FooTest {
                            AxonTestFixture testFixture;
                            @BeforeEach
                            void setUp() {
                                testFixture = AxonTestFixture.with(null);
                            }

                            @AfterEach
                            void tearDown() {
                                testFixture.stop();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void noOpWhenNoBeforeEachLifecycleSignal() {
        // The @BeforeEach precondition keeps the recipe from firing on
        // non-JUnit-5 classes (and on the recipe-test toy fixtures that
        // are not real test classes) — even when an `AxonTestFixture`
        // field is present.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        class FooTest {
                            AxonTestFixture fixture;
                        }
                        """
                )
        );
    }

    @Test
    void noOpWhenAfterEachAlreadyPresent() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        import org.junit.jupiter.api.AfterEach;
                        class FooTest {
                            AxonTestFixture fixture;
                            @AfterEach
                            void cleanup() {
                                // user-owned cleanup; do not append a sibling
                            }
                        }
                        """
                )
        );
    }

    @Test
    void noOpWhenTearDownMethodAlreadyExists() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        class FooTest {
                            AxonTestFixture fixture;
                            void tearDown() {
                                // existing method (no @AfterEach) — leave alone, do not duplicate
                            }
                        }
                        """
                )
        );
    }

    @Test
    void noOpWhenNoAxonTestFixtureField() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        class FooTest {
                            String unrelated;
                        }
                        """
                )
        );
    }

    @Test
    void noOpWhenFieldIsAf4AggregateTestFixture() {
        // Recipe must wait for the ChangeType pass above it in the recipeList to
        // rename the type to AF5 before generating the tear-down. Pre-rename the
        // recipe is a deliberate no-op.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                        }
                        """
                )
        );
    }

    @Test
    void idempotentOnRerun() {
        // Running the recipe a second time over an already-migrated class detects
        // the existing @AfterEach and does nothing.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        import org.junit.jupiter.api.AfterEach;
                        class FooTest {
                            AxonTestFixture fixture;

                            @AfterEach
                            void tearDown() {
                                fixture.stop();
                            }
                        }
                        """
                )
        );
    }
}
