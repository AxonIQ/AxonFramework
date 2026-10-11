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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link MarkUnsupportedSagaTestFixtureCalls}.
 */
class MarkUnsupportedSagaTestFixtureCallsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkUnsupportedSagaTestFixtureCalls())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class FixtureConfigurationCalls {

        @Test
        void keepsTheTransienceCheckCallAndMarksItForDeletion() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void setUp() {
                                    fixture = new SagaTestFixture<>(Object.class);
                                    fixture.withTransienceCheckDisabled();
                                    fixture.withTransienceCheckDisabled()
                                           .registerIgnoredField(Object.class, "field");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void setUp() {
                                    fixture = new SagaTestFixture<>(Object.class);
                                    // TODO(axon4to5): delete this withTransienceCheckDisabled() call. It is a deprecated-for-removal no-op in axoniq-legacy-test: Sagas get no field injection, so there is no transience check to disable.
                                    fixture.withTransienceCheckDisabled();
                                    // TODO(axon4to5): delete this withTransienceCheckDisabled() call. It is a deprecated-for-removal no-op in axoniq-legacy-test: Sagas get no field injection, so there is no transience check to disable.
                                    fixture.withTransienceCheckDisabled()
                                           .registerIgnoredField(Object.class, "field");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksTheRemovedGatewayAndErrorHandlerRegistrations() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventhandling.PropagatingErrorHandler;
                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void setUp() {
                                    fixture.registerCommandGateway(Runnable.class);
                                    fixture.registerListenerInvocationErrorHandler(PropagatingErrorHandler.instance());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventhandling.PropagatingErrorHandler;
                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void setUp() {
                                    // TODO(axon4to5): replace registerCommandGateway(..), which axoniq-legacy-test does not have. Axon Framework 5 cannot build a custom gateway interface; let the Saga take a CommandDispatcher or CommandGateway handler parameter, and answer its commands with setCallbackBehavior(..) or a command handler registered through customize(..).
                                    fixture.registerCommandGateway(Runnable.class);
                                    // TODO(axon4to5): replace registerListenerInvocationErrorHandler(..), which axoniq-legacy-test does not have. Axon Framework 5 has no ListenerInvocationErrorHandler; a Saga handles its own failures in an @ExceptionHandler method.
                                    fixture.registerListenerInvocationErrorHandler(PropagatingErrorHandler.instance());
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksAFixtureDeclaredAsTheFixtureConfigurationInterface() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.FixtureConfiguration;
                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                private FixtureConfiguration fixture = new SagaTestFixture<>(Object.class);
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.FixtureConfiguration;
                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                // TODO(axon4to5): declare this fixture as SagaTestFixture and call stop() on it in an @AfterEach method. The ported fixture runs a started configuration, and FixtureConfiguration has no stop().
                                private FixtureConfiguration fixture = new SagaTestFixture<>(Object.class);
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ScheduledEventAssertions {

        @Test
        void marksAScheduledEventAssertionInAFluentChainOnTheStatementLine() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import java.time.Duration;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void schedulesNothing() {
                                    fixture.givenAPublished("started")
                                           .whenTimeElapses(Duration.ofMinutes(1))
                                           .expectNoScheduledEvents()
                                           .expectActiveSagas(1)
                                           .expectScheduledEvent(Duration.ofMinutes(1), "reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import java.time.Duration;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void schedulesNothing() {
                                    // TODO(axon4to5): remove or replace this scheduled event assertion. axoniq-legacy-test does not port the event scheduler, so expectScheduledEvent*, expectNoScheduledEvent* and expectNoScheduledEvents() throw UnsupportedOperationException.
                                    fixture.givenAPublished("started")
                                           .whenTimeElapses(Duration.ofMinutes(1))
                                           .expectNoScheduledEvents()
                                           .expectActiveSagas(1)
                                           .expectScheduledEvent(Duration.ofMinutes(1), "reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksAnAssertionInsideALambdaOnTheEnclosingStatement() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.FixtureExecutionResult;

                            class PaymentSagaTest {
                                void assertsLater(FixtureExecutionResult result, java.util.function.Consumer<Runnable> check) {
                                    check.accept(() -> result.expectNoScheduledEventOfType(java.time.Duration.ZERO, String.class));
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.FixtureExecutionResult;

                            class PaymentSagaTest {
                                void assertsLater(FixtureExecutionResult result, java.util.function.Consumer<Runnable> check) {
                                    // TODO(axon4to5): remove or replace this scheduled event assertion. axoniq-legacy-test does not port the event scheduler, so expectScheduledEvent*, expectNoScheduledEvent* and expectNoScheduledEvents() throw UnsupportedOperationException.
                                    check.accept(() -> result.expectNoScheduledEventOfType(java.time.Duration.ZERO, String.class));
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class UsageLeftAlone {

        @Test
        void leavesSupportedFixtureCallsAndASagaTestFixtureFieldAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import java.time.Duration;

                            class PaymentSagaTest {
                                private SagaTestFixture<Object> fixture = new SagaTestFixture<>(Object.class);

                                void schedulesADeadline() {
                                    fixture.givenAPublished("started")
                                           .whenTimeElapses(Duration.ofMinutes(1))
                                           .expectScheduledDeadline(Duration.ofMinutes(1), "reminder")
                                           .expectNoScheduledDeadlines();
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

                            class Scheduler {
                                Scheduler withTransienceCheckDisabled() {
                                    return this;
                                }

                                void expectNoScheduledEvents() {
                                }

                                void use() {
                                    withTransienceCheckDisabled().expectNoScheduledEvents();
                                }
                            }
                            """
                    )
            );
        }
    }
}
