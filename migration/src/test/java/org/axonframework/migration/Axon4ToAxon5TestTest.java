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
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the test-module fixture migration: {@code AggregateTestFixture}
 * and {@code SagaTestFixture} both rename to the unified
 * {@code AxonTestFixture}, and AF4-style flat fixture call chains
 * (`fixture.given(...).when(...).expectEvents(...)`) are rewritten to the
 * AF5 fluent given/when/then form.
 */
class Axon4ToAxon5TestTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5Test"))
                // The AF5 AxonTestFixture isn't on the test classpath; without disabling
                // type validation OpenRewrite would reject the post-rewrite tree. The
                // recipe itself does not depend on type information for the call-chain
                // rewrite — it matches by method name and argument shape.
                .typeValidationOptions(TypeValidation.none());
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
                            AxonTestFixture fixture;
                        }
                        """
                )
        );
    }

    @Test
    void rewritesGivenEventsWhenCommandExpectEvents() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.given(new Object())
                                       .when(new Object())
                                       .expectEvents(new Object());
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .events(new Object())
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .events(new Object());
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesGivenNoPriorActivityAndExpectNoEvents() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.givenNoPriorActivity()
                                       .when(new Object())
                                       .expectNoEvents();
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .noPriorActivity()
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .noEvents();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesGivenCommandsSingleArgToCommand() {
        // Single-argument `givenCommands` collapses to the AF5 singular `command(...)` form.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.givenCommands(new Object())
                                       .when(new Object())
                                       .expectSuccessfulHandlerExecution();
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .command(new Object())
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .success();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesGivenCommandsMultipleArgsToCommandsVarargs() {
        // Multi-argument `givenCommands` keeps the plural `commands(...)` form.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.givenCommands(new Object(), new Object())
                                       .when(new Object())
                                       .expectSuccessfulHandlerExecution();
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .commands(new Object(), new Object())
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .success();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void mergesExpectExceptionAndExpectExceptionMessageIntoSingleCall() {
        // AF4's two-call shape `.expectException(X).expectExceptionMessage(M)` becomes the AF5
        // single-call form `.then().exception(X, M)` — anything else would leave a dangling
        // builder method whose AF5 equivalent doesn't exist.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.given(new Object())
                                       .when(new Object())
                                       .expectException(IllegalStateException.class)
                                       .expectExceptionMessage("boom");
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .events(new Object())
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .exception(IllegalStateException.class, "boom");
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesExpectResultMessagePayload() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.givenNoPriorActivity()
                                       .when(new Object())
                                       .expectResultMessagePayload("ok");
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .noPriorActivity()
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .resultMessagePayload("ok");
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesGivenWithEventListArgument() {
        // `given(List<?>)` overload also funnels through `events(...)` — AF5's
        // `events(List<?>)` overload accepts the same shape.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import java.util.List;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.given(List.of(new Object(), new Object()))
                                       .when(new Object())
                                       .expectSuccessfulHandlerExecution();
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;

                        import java.util.List;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .events(List.of(new Object(), new Object()))
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .success();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void rewritesAggregateTestFixtureSetupWithFallbackIdType() {
        // No `@AggregateIdentifier` field is visible to the recipe (the
        // aggregate class isn't part of the rewritten source set), so the
        // id type falls back to `Object.class` with a TODO #LLM marker.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Foo> fixture;
                            void setUp() {
                                fixture = new AggregateTestFixture<>(Foo.class);
                            }
                        }
                        class Foo {}
                        """,
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
                        import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void setUp() {
                                fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(EventSourcedEntityModule.autodetected(Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */, Foo.class)));
                            }
                        }
                        class Foo {}
                        """
                )
        );
    }

    @Test
    void rewritesAggregateTestFixtureSetupDeducingIdTypeFromAggregateIdentifierField() {
        // End-to-end: the aggregate has an `@AggregateIdentifier` field of
        // type `UUID`. `AddEventTagAnnotation`'s scanner publishes that to
        // the shared `ExecutionContext`, and `MigrateAggregateTestFixtureSetup`
        // reads it to emit `EventSourcedEntityModule.autodetected(UUID.class, Astrologers.class)`.
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
                        .typeValidationOptions(TypeValidation.none())
                        .expectedCyclesThatMakeChanges(1),
                java(
                        """
                        package com.example;
                        import java.util.UUID;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;
                        @Aggregate
                        class Astrologers {
                            @AggregateIdentifier
                            private UUID astrologersId;
                            Astrologers() { }
                        }
                        """,
                        """
                        package com.example;
                        import java.util.UUID;

                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
                        import org.axonframework.extension.spring.stereotype.EventSourced;

                        @EventSourced(tagKey = "Astrologers", idType = UUID.class)
                        class Astrologers {
                            private UUID astrologersId;

                            @EntityCreator
                            Astrologers() { }
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class AstrologersTest {
                            AggregateTestFixture<Astrologers> fixture;
                            void setUp() {
                                fixture = new AggregateTestFixture<>(Astrologers.class);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
                        import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
                        import org.axonframework.test.fixture.AxonTestFixture;

                        import java.util.UUID;

                        class AstrologersTest {
                            AxonTestFixture fixture;
                            void setUp() {
                                fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(EventSourcedEntityModule.autodetected(UUID.class, Astrologers.class)));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesSagaTestFixtureSetupAlone() {
        // The new setup recipe matches the AF4 AggregateTestFixture FQN only,
        // so SagaTestFixture constructors are left untouched (only the type
        // rename runs against them).
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.saga.SagaTestFixture;
                        class FooSagaTest {
                            SagaTestFixture<FooSaga> fixture;
                            void setUp() {
                                fixture = new SagaTestFixture<>(FooSaga.class);
                            }
                        }
                        class FooSaga {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooSagaTest {
                            AxonTestFixture fixture;
                            void setUp() {
                                fixture = new AxonTestFixture(FooSaga.class);
                            }
                        }
                        class FooSaga {}
                        """
                )
        );
    }

    @Test
    void coalescesChainedExpectCallsUnderSingleThen() {
        // `.expectNoEvents().expectException(X)` are both `then`-phase calls, so the rewrite must
        // produce a single shared `.then()` followed by both leaves — not two independent
        // `.then().noEvents()` and `.then().exception(X)` chains stacked on top of each other.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        class FooTest {
                            AggregateTestFixture<Object> fixture;
                            void test() {
                                fixture.given(new Object())
                                       .when(new Object())
                                       .expectNoEvents()
                                       .expectException(IllegalStateException.class);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.fixture.AxonTestFixture;

                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .events(new Object())
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .noEvents()
                                       .exception(IllegalStateException.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesAlreadyMigratedFluentChainAlone() {
        // Running the recipe on AF5-shaped code should be idempotent: `given()` / `when()`
        // with no arguments are the new phase entry points and must not be rewritten.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.test.fixture.AxonTestFixture;
                        class FooTest {
                            AxonTestFixture fixture;
                            void test() {
                                fixture.given()
                                       .events(new Object())
                                       .when()
                                       .command(new Object())
                                       .then()
                                       .events(new Object());
                            }
                        }
                        """
                )
        );
    }
}
