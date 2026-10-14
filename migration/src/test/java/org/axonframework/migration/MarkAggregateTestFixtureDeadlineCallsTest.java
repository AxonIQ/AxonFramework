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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;

/**
 * Verifies that the deadline and time calls of the Axon Framework 4 aggregate test fixture are kept and marked.
 */
class MarkAggregateTestFixtureDeadlineCallsTest implements RewriteTest {

    private static final String FREE_DEADLINE_TEST_TODO =
            "// TODO(axon4to5): rewrite this aggregate deadline test without the fixture's deadline and time calls."
                    + " AxonTestFixture has no deadline manager and no clock to advance, and Axon Framework 5 has no"
                    + " deadlines; Axoniq Framework keeps them running on axoniq-legacy.";
    private static final String LEGACY_DEADLINE_TEST_TODO =
            "// TODO(axon4to5): rewrite this aggregate deadline test without the fixture's deadline and time calls."
                    + " AxonTestFixture has no deadline manager and no clock to advance, and axoniq-legacy-test"
                    + " supports deadlines on SagaTestFixture only. A fired aggregate deadline is now the command its"
                    + " migrated @CommandHandler receives: send it with when().command(<deadline payload>,"
                    + " Metadata.with(AggregateDeadlineEntityIdResolverDefinition.DESCRIPTOR_BASED_ID, <aggregate"
                    + " identifier>)), where a deadline without payload is a command named after, and carrying, the"
                    + " deadline name. Assert the scheduling on a StubDeadlineManager registered as the"
                    + " DeadlineManager component.";
    private static final String FREE_INTERCEPTOR_TODO =
            "// TODO(axon4to5): move this deadline interceptor registration off the fixture, or remove it."
                    + " AxonTestFixture has no deadline manager to intercept, and Axon Framework 5 has no deadlines;"
                    + " Axoniq Framework keeps them running on axoniq-legacy.";
    private static final String LEGACY_INTERCEPTOR_TODO =
            "// TODO(axon4to5): move this deadline interceptor registration off the fixture. AxonTestFixture has"
                    + " no deadline manager to intercept; register the interceptor, in its Axon Framework 5"
                    + " signature, on the deadline manager the test uses, such as a StubDeadlineManager.";

    /**
     * An Axon Framework 4 aggregate deadline test, modelled on the Axon Framework 4 {@code FixtureTest_Deadlines}.
     */
    private static final String ORDER_TEST = """
            package com.example;

            import org.axonframework.test.aggregate.AggregateTestFixture;
            import org.axonframework.test.aggregate.FixtureConfiguration;
            import org.axonframework.test.aggregate.ResultValidator;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Test;

            import java.time.Duration;
            import java.time.Instant;

            import static org.junit.jupiter.api.Assertions.assertThrows;

            class OrderTest {

                private FixtureConfiguration<Order> fixture;

                @BeforeEach
                void setUp() {
                    fixture = new AggregateTestFixture<>(Order.class);
                }

                @Test
                void schedulesThePaymentDeadline() {
                    fixture.given(new OrderPlaced("order-1"))
                           .when(new ConfirmOrder("order-1"))
                           .expectEvents(new OrderConfirmed("order-1"))
                           .expectScheduledDeadline(Duration.ofDays(1), new PaymentDeadline("order-1"));
                }

                @Test
                void cancelsTheOrderWhenThePaymentDeadlinePasses() {
                    fixture.givenCurrentTime(Instant.parse("2026-01-01T00:00:00Z"))
                           .andGivenCommands(new ConfirmOrder("order-1"))
                           .whenTimeElapses(Duration.ofDays(2))
                           .expectTriggeredDeadlinesOfType(PaymentDeadline.class)
                           .expectEvents(new OrderCancelled("order-1"));
                }

                @Test
                void paymentCancelsThePaymentDeadline() {
                    fixture.given(new OrderPlaced("order-1"), new OrderConfirmed("order-1"))
                           .when(new PayOrder("order-1"))
                           .expectNoScheduledDeadlines();
                }

                @Test
                void rejectsAnotherTriggeredDeadline() {
                    ResultValidator<Order> result = fixture.givenCommands(new ConfirmOrder("order-1"))
                                                           .whenThenTimeElapses(Duration.ofDays(2));

                    assertThrows(AssertionError.class,
                                 () -> result.expectTriggeredDeadlines(new PaymentDeadline("order-2")));
                }

                @Test
                void interceptsThePaymentDeadline() {
                    fixture.registerDeadlineDispatchInterceptor(messages -> (index, message) -> message)
                           .givenNoPriorActivity()
                           .andGivenCommands(new ConfirmOrder("order-1"))
                           .whenTimeElapses(Duration.ofDays(2))
                           .expectTriggeredDeadlinesOfType(PaymentDeadline.class);
                }

                @Test
                void confirmsThePlacedOrder() {
                    fixture.given(new OrderPlaced("order-1"))
                           .when(new ConfirmOrder("order-1"))
                           .expectEvents(new OrderConfirmed("order-1"));
                }
            }
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkAggregateTestFixtureDeadlineCalls(null))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class FreeWording {

        @Test
        void marksDeadlineAssertionsTimeCallsAndInterceptorRegistrationsAndKeepsTheCalls() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            import java.time.Duration;
                            import java.time.Instant;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void setUp() {
                                    fixture.registerDeadlineHandlerInterceptor((unitOfWork, chain) -> chain.proceed());
                                }

                                void schedules() {
                                    fixture.given("placed")
                                           .when("confirm")
                                           .expectScheduledDeadlineWithName(Duration.ofDays(1), "payment");
                                }

                                void triggers() {
                                    fixture.givenCurrentTime(Instant.EPOCH)
                                           .whenTimeAdvancesTo(Instant.EPOCH.plusSeconds(60))
                                           .expectDeadlinesMet("payment");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            import java.time.Duration;
                            import java.time.Instant;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void setUp() {
                                    %s
                                    fixture.registerDeadlineHandlerInterceptor((unitOfWork, chain) -> chain.proceed());
                                }

                                void schedules() {
                                    %s
                                    fixture.given("placed")
                                           .when("confirm")
                                           .expectScheduledDeadlineWithName(Duration.ofDays(1), "payment");
                                }

                                void triggers() {
                                    %s
                                    fixture.givenCurrentTime(Instant.EPOCH)
                                           .whenTimeAdvancesTo(Instant.EPOCH.plusSeconds(60))
                                           .expectDeadlinesMet("payment");
                                }
                            }
                            """.formatted(FREE_INTERCEPTOR_TODO, FREE_DEADLINE_TEST_TODO, FREE_DEADLINE_TEST_TODO)
                    )
            );
        }

        @Test
        void marksAStatementOnceForEachKindOfCall() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            import java.time.Duration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void intercepts() {
                                    fixture.registerDeadlineDispatchInterceptor(messages -> (index, message) -> message)
                                           .givenNoPriorActivity()
                                           .whenTimeElapses(Duration.ofDays(2))
                                           .expectTriggeredDeadlines("payment")
                                           .expectNoScheduledDeadlines();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            import java.time.Duration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void intercepts() {
                                    %s
                                    %s
                                    fixture.registerDeadlineDispatchInterceptor(messages -> (index, message) -> message)
                                           .givenNoPriorActivity()
                                           .whenTimeElapses(Duration.ofDays(2))
                                           .expectTriggeredDeadlines("payment")
                                           .expectNoScheduledDeadlines();
                                }
                            }
                            """.formatted(FREE_INTERCEPTOR_TODO, FREE_DEADLINE_TEST_TODO)
                    )
            );
        }

        @Test
        void marksTheStatementHoldingADeadlineAssertionInALambdaExpression() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.ResultValidator;

                            import static org.junit.jupiter.api.Assertions.assertThrows;

                            class OrderTest {

                                void rejects(ResultValidator<Object> result) {
                                    assertThrows(AssertionError.class, () -> result.expectTriggeredDeadlines("other"));
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.ResultValidator;

                            import static org.junit.jupiter.api.Assertions.assertThrows;

                            class OrderTest {

                                void rejects(ResultValidator<Object> result) {
                                    %s
                                    assertThrows(AssertionError.class, () -> result.expectTriggeredDeadlines("other"));
                                }
                            }
                            """.formatted(FREE_DEADLINE_TEST_TODO)
                    )
            );
        }

        @Test
        void marksAnUnresolvedCallInAFileUsingTheAggregateFixture() {
            // The fixture type does not resolve, so the call is recognized by its name and the aggregate fixture
            // import.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.UnresolvedFixture;

                            class OrderTest {

                                UnresolvedFixture fixture;

                                void triggers() {
                                    fixture.whenThenTimeElapses(java.time.Duration.ofDays(2));
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.UnresolvedFixture;

                            class OrderTest {

                                UnresolvedFixture fixture;

                                void triggers() {
                                    %s
                                    fixture.whenThenTimeElapses(java.time.Duration.ofDays(2));
                                }
                            }
                            """.formatted(FREE_DEADLINE_TEST_TODO)
                    )
            );
        }

        @Test
        void leavesAggregateFixtureCallsWithoutDeadlinesOrTimeAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void confirms() {
                                    fixture.given("placed")
                                           .when("confirm")
                                           .expectEvents("confirmed");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesTheDeadlineCallsOfTheSagaFixtureAlone() {
            // The ported SagaTestFixture keeps its deadline and time support.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import java.time.Duration;
                            import java.time.Instant;

                            class PaymentSagaTest {

                                SagaTestFixture<Object> fixture;

                                void triggers() {
                                    fixture.givenCurrentTime(Instant.EPOCH)
                                           .whenTimeElapses(Duration.ofMinutes(11))
                                           .expectTriggeredDeadlines("details");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSameNamedMethodsOfOtherTypesAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            class Clock {

                                Clock whenTimeElapses(Duration duration) {
                                    return this;
                                }

                                void currentTime() {
                                }

                                void tick() {
                                    whenTimeElapses(Duration.ofDays(1)).currentTime();
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class AxoniqLegacyWording {

        @Test
        void pointsTheDeadlineTestAtTheCommandTheDeadlineBecomes() {
            rewriteRun(
                    spec -> spec.recipe(new MarkAggregateTestFixtureDeadlineCalls(true)),
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            import java.time.Duration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void setUp() {
                                    fixture.registerDeadlineDispatchInterceptor(messages -> (index, message) -> message);
                                }

                                void triggers() {
                                    fixture.givenCommands("confirm")
                                           .whenTimeElapses(Duration.ofDays(2))
                                           .expectTriggeredDeadlinesWithName("payment");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            import java.time.Duration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void setUp() {
                                    %s
                                    fixture.registerDeadlineDispatchInterceptor(messages -> (index, message) -> message);
                                }

                                void triggers() {
                                    %s
                                    fixture.givenCommands("confirm")
                                           .whenTimeElapses(Duration.ofDays(2))
                                           .expectTriggeredDeadlinesWithName("payment");
                                }
                            }
                            """.formatted(LEGACY_INTERCEPTOR_TODO, LEGACY_DEADLINE_TEST_TODO)
                    )
            );
        }

        @Test
        void keepsTheAxoniqLegacyMarkerWhenTheFreeWordingRunsAfterIt() {
            // The commercial upgrade marks the statement first; the free leg then finds the shared marker key.
            rewriteRun(
                    spec -> spec.recipes(new MarkAggregateTestFixtureDeadlineCalls(true),
                                         new MarkAggregateTestFixtureDeadlineCalls(false)),
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void paymentCancelsTheDeadline() {
                                    fixture.given("confirmed")
                                           .when("pay")
                                           .expectNoScheduledDeadlines();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.aggregate.FixtureConfiguration;

                            class OrderTest {

                                FixtureConfiguration<Object> fixture;

                                void paymentCancelsTheDeadline() {
                                    %s
                                    fixture.given("confirmed")
                                           .when("pay")
                                           .expectNoScheduledDeadlines();
                                }
                            }
                            """.formatted(LEGACY_DEADLINE_TEST_TODO)
                    )
            );
        }
    }

    /**
     * The free leg changes the tree of the deadline interceptor lambda once more in a second cycle, without changing
     * its text, with or without the marker, so these runs expect two cycles that make changes.
     */
    @Nested
    class FullUpgrade {

        @Test
        void freeUpgradeKeepsAndMarksEveryDeadlineAndTimeCallThroughTheFluentRewrite() {
            rewriteRun(
                    spec -> spec.recipe(recipe("org.axonframework.migration.UpgradeAxon4ToAxon5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY)
                                .expectedCyclesThatMakeChanges(2),
                    domain(),
                    srcTestJava(java(ORDER_TEST, source -> source.after(actual -> {
                        assertMarked(actual, FREE_DEADLINE_TEST_TODO, FREE_INTERCEPTOR_TODO);
                        return actual;
                    })))
            );
        }

        @Test
        void commercialUpgradeKeepsAndMarksEveryDeadlineAndTimeCallWithTheAxoniqLegacyWording() {
            rewriteRun(
                    spec -> spec.recipe(recipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY)
                                .expectedCyclesThatMakeChanges(2),
                    domain(),
                    srcTestJava(java(ORDER_TEST, source -> source.after(actual -> {
                        assertMarked(actual, LEGACY_DEADLINE_TEST_TODO, LEGACY_INTERCEPTOR_TODO);
                        assertThat(actual).doesNotContain(FREE_DEADLINE_TEST_TODO)
                                          .doesNotContain(FREE_INTERCEPTOR_TODO);
                        return actual;
                    })))
            );
        }

        /**
         * Asserts each deadline or time statement sits right below its marker, in the shape the fluent rewrite gave
         * the surrounding chain, with every Axon Framework 4 deadline and time call kept.
         */
        private static void assertMarked(String actual, String deadlineTestTodo, String interceptorTodo) {
            assertThat(actual)
                    .contains("""
                                      void schedulesThePaymentDeadline() {
                                              %s
                                              fixture.given()
                                                     .events(new OrderPlaced("order-1"))
                                                     .when()
                                                     .command(new ConfirmOrder("order-1"))
                                                     .then()
                                                     .events(new OrderConfirmed("order-1"))
                                                     .expectScheduledDeadline(Duration.ofDays(1), new PaymentDeadline("order-1"));
                                      """.formatted(deadlineTestTodo))
                    .contains("""
                                      void cancelsTheOrderWhenThePaymentDeadlinePasses() {
                                              %s
                                              fixture.givenCurrentTime(Instant.parse("2026-01-01T00:00:00Z"))
                                                     .andGivenCommands(new ConfirmOrder("order-1"))
                                                     .whenTimeElapses(Duration.ofDays(2))
                                                     .expectTriggeredDeadlinesOfType(PaymentDeadline.class)
                                                     .then()
                                                     .events(new OrderCancelled("order-1"));
                                      """.formatted(deadlineTestTodo))
                    .contains("""
                                      void paymentCancelsThePaymentDeadline() {
                                              %s
                                              fixture.given()
                                                     .events(new OrderPlaced("order-1"), new OrderConfirmed("order-1"))
                                                     .when()
                                                     .command(new PayOrder("order-1"))
                                                     .expectNoScheduledDeadlines();
                                      """.formatted(deadlineTestTodo))
                    .contains("""
                                      void rejectsAnotherTriggeredDeadline() {
                                              %s
                                              ResultValidator<Order> result = fixture.given()
                                                                                     .command(new ConfirmOrder("order-1"))
                                                                                     .whenThenTimeElapses(Duration.ofDays(2));

                                              %s
                                              assertThrows(AssertionError.class,
                                                           () -> result.expectTriggeredDeadlines(new PaymentDeadline("order-2")));
                                      """.formatted(deadlineTestTodo, deadlineTestTodo))
                    .contains("""
                                      void interceptsThePaymentDeadline() {
                                              %s
                                              %s
                                              fixture.registerDeadlineDispatchInterceptor(messages -> (index, message) -> message)
                                                     .given()
                                                     .noPriorActivity()
                                                     .andGivenCommands(new ConfirmOrder("order-1"))
                                                     .whenTimeElapses(Duration.ofDays(2))
                                                     .expectTriggeredDeadlinesOfType(PaymentDeadline.class);
                                      """.formatted(interceptorTodo, deadlineTestTodo))
                    .contains("""
                                      void confirmsThePlacedOrder() {
                                              fixture.given()
                                      """);
        }
    }

    private static SourceSpecs domain() {
        return srcMainJava(
                java(record("OrderPlaced")),
                java(record("ConfirmOrder")),
                java(record("OrderConfirmed")),
                java(record("PayOrder")),
                java(record("OrderCancelled")),
                java(record("PaymentDeadline")),
                java("""
                     package com.example;

                     public class Order {
                     }
                     """)
        );
    }

    private static String record(String name) {
        return """
                package com.example;

                public record %s(String orderId) {
                }
                """.formatted(name);
    }

    private static Recipe recipe(String name) {
        return Environment.builder()
                          .scanRuntimeClasspath("org.axonframework.migration")
                          .build()
                          .activateRecipes(name);
    }
}
