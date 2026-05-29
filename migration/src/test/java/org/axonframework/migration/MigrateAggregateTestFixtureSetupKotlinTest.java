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
 * Verifies the {@link MigrateAggregateTestFixtureSetup} recipe on Kotlin sources.
 * <p>
 * The Kotlin path uses {@code KotlinTemplate} so the synthesized AF5 setup chain
 * carries Kotlin-shaped class literals ({@code X::class.java}) instead of the
 * Java {@code X.class} form.
 */
class MigrateAggregateTestFixtureSetupKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The migration module ships only AF4 type stubs in test scope, so the
        // AF5 types this recipe synthesizes have no resolvable bindings.
        spec.recipe(new MigrateAggregateTestFixtureSetup())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void rewritesKotlinAggregateTestFixtureSetupWithFallbackIdType() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.test.aggregate.AggregateTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Foo>
                            fun setUp() {
                                fixture = AggregateTestFixture(Foo::class.java)
                            }
                        }
                        class Foo
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule
                        import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
                        import org.axonframework.test.aggregate.AggregateTestFixture
                        import org.axonframework.test.fixture.AxonTestFixture

                        class FooTest {
                            lateinit var fixture: AggregateTestFixture<Foo>
                            fun setUp() {
                                fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(EventSourcedEntityModule.autodetected(Object::class.java /* TODO(axon4to5): set to actual id type, e.g. String.class or UUID.class */, Foo::class.java)))
                            }
                        }
                        class Foo
                        """
                )
        );
    }

    @Test
    void leavesAlreadyMigratedKotlinSetupAlone() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule
                        import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
                        import org.axonframework.test.fixture.AxonTestFixture

                        class FooTest {
                            lateinit var fixture: AxonTestFixture
                            fun setUp() {
                                fixture = AxonTestFixture.with(
                                    EventSourcingConfigurer.create()
                                        .registerEntity(EventSourcedEntityModule.autodetected(Object::class.java, Foo::class.java))
                                )
                            }
                        }
                        class Foo
                        """
                )
        );
    }

    @Test
    void leavesKotlinSagaTestFixtureSetupAlone() {
        // The recipe targets the AF4 AggregateTestFixture FQN only — Kotlin SagaTestFixture
        // setups are passed through (the type rename happens in a different recipe).
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.test.saga.SagaTestFixture

                        class FooSagaTest {
                            lateinit var fixture: SagaTestFixture<FooSaga>
                            fun setUp() {
                                fixture = SagaTestFixture(FooSaga::class.java)
                            }
                        }
                        class FooSaga
                        """
                )
        );
    }

    @Test
    void doesNotTouchUnrelatedKotlinNewClassExpressions() {
        // Sanity: only AggregateTestFixture constructors trigger the rewrite.
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        class Bar {
                            fun build() {
                                val list = ArrayList<String>()
                                list.add("hi")
                            }
                        }
                        """
                )
        );
    }
}
