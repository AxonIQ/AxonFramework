package org.axonframework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

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
}
