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

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies the {@link MigrateAxonTestFixtureFluentApi} recipe on Kotlin sources.
 * <p>
 * The synthesized AF5 phase entrypoint for {@code when(...)} must be backtick-escaped
 * to {@code `` `when` ``} because {@code when} is a Kotlin hard keyword and would
 * otherwise be parsed as a {@code when} expression rather than a method call.
 * The other phase entrypoints ({@code given}, {@code then}) and the leaf method names
 * ({@code events}, {@code command}, …) are not Kotlin keywords and stay unescaped.
 */
class MigrateAxonTestFixtureFluentApiKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The migration module ships only AF4 type stubs in test scope, so the AF5
        // AxonTestFixture and its fluent API have no resolvable bindings.
        spec.recipe(new MigrateAxonTestFixtureFluentApi())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void rewritesKotlinChainBacktickEscapingWhen() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given(Any())
                                       .`when`(Any())
                                       .expectEvents(Any())
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given()
                                       .events(Any())
                                       .`when`()
                                       .command(Any())
                                       .then()
                                       .events(Any())
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesSingleLineKotlinChain() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given(Any()).`when`(Any()).expectEvents(Any())
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given().events(Any()).`when`().command(Any()).then().events(Any())
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesAlreadyMigratedKotlinChainAlone() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given().events(Any())
                                       .`when`().command(Any())
                                       .then().events(Any())
                            }
                        }
                        """
                )
        );
    }

    @Test
    void coalescesSamePhaseLeavesUnderSingleThenInKotlin() {
        // AF4 lets you stack `expectNoEvents().expectException(X::class.java)`. The recipe
        // must not stack two `then()` entrypoints — one shared `then()` is enough.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given(Any())
                                       .`when`(Any())
                                       .expectNoEvents()
                                       .expectException(IllegalStateException::class.java)
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Any>
                            fun test() {
                                fixture.given()
                                       .events(Any())
                                       .`when`()
                                       .command(Any())
                                       .then()
                                       .noEvents()
                                       .exception(IllegalStateException::class.java)
                            }
                        }
                        """
                )
        );
    }
}
