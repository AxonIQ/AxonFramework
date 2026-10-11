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
import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateAxonTestFixtureFluentApiSagaTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAxonTestFixtureFluentApi())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void leavesSagaTestFixtureChainsAlone() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.test.saga.SagaTestFixture;
                        import org.junit.jupiter.api.Test;

                        class OrderSagaTest {
                            private final SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);

                            @Test
                            void startsOnOrderPlaced() {
                                fixture.givenNoPriorActivity()
                                       .whenPublishingA(new OrderPlaced("o-1"))
                                       .expectActiveSagas(1)
                                       .expectDispatchedCommands(new ReserveStock("o-1"));
                            }

                            @Test
                            void endsOnCompletion() {
                                fixture.givenAggregate("o-1").published(new OrderPlaced("o-1"))
                                       .whenPublishingA(new OrderCompleted("o-1"))
                                       .expectActiveSagas(0)
                                       .expectNoDispatchedCommands();
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesSagaFixtureChainsAloneWhenTheSagaPackageIsImportedWithAWildcard() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.test.saga.*;
                        import org.junit.jupiter.api.Test;

                        class OrderSagaTest {
                            private final SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);

                            @Test
                            void startsOnOrderPlaced() {
                                fixture.givenNoPriorActivity()
                                       .whenPublishingA(new OrderPlaced("o-1"))
                                       .expectActiveSagas(1);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesSagaFixtureChainsAloneWhenTheFixtureIsFullyQualified() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.junit.jupiter.api.Test;

                        class OrderSagaTest {
                            private final org.axonframework.test.saga.SagaTestFixture<OrderSaga> fixture =
                                    new org.axonframework.test.saga.SagaTestFixture<>(OrderSaga.class);

                            @Test
                            void startsOnOrderPlaced() {
                                fixture.givenNoPriorActivity()
                                       .whenPublishingA(new OrderPlaced("o-1"))
                                       .expectActiveSagas(1);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesSagaFixtureChainsAloneWhenOnlyTheSagaFixtureConfigurationIsImported() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.test.saga.FixtureConfiguration;
                        import org.junit.jupiter.api.Test;

                        abstract class OrderSagaTest {
                            abstract FixtureConfiguration fixture();

                            @Test
                            void startsOnOrderPlaced() {
                                fixture().givenNoPriorActivity()
                                         .whenPublishingA(new OrderPlaced("o-1"))
                                         .expectActiveSagas(1);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void stillRewritesAggregateFixtureChainsInFilesWithoutSagaFixtureTypes() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        import org.junit.jupiter.api.Test;

                        class OrderTest {
                            private final AggregateTestFixture<Order> fixture = new AggregateTestFixture<>(Order.class);

                            @Test
                            void placesOrder() {
                                fixture.givenNoPriorActivity()
                                       .when(new PlaceOrder("o-1"))
                                       .expectEvents(new OrderPlaced("o-1"));
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.test.aggregate.AggregateTestFixture;
                        import org.junit.jupiter.api.Test;

                        class OrderTest {
                            private final AggregateTestFixture<Order> fixture = new AggregateTestFixture<>(Order.class);

                            @Test
                            void placesOrder() {
                                fixture.given()
                                       .noPriorActivity()
                                       .when()
                                       .command(new PlaceOrder("o-1"))
                                       .then()
                                       .events(new OrderPlaced("o-1"));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesKotlinSagaTestFixtureChainsAlone() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        import org.axonframework.test.saga.SagaTestFixture
                        import org.junit.jupiter.api.Test

                        class OrderSagaTest {
                            private val fixture = SagaTestFixture(OrderSaga::class.java)

                            @Test
                            fun startsOnOrderPlaced() {
                                fixture.givenNoPriorActivity()
                                       .whenPublishingA(OrderPlaced("o-1"))
                                       .expectActiveSagas(1)
                                       .expectDispatchedCommands(ReserveStock("o-1"))
                            }
                        }
                        """
                )
        );
    }
}
