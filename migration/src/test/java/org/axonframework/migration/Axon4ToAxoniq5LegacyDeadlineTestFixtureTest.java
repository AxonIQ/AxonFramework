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
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.srcTestJava;

/**
 * Verifies migration of Axon Framework 4 deadline test fixtures onto {@code io.axoniq.framework:axoniq-legacy-test}.
 */
class Axon4ToAxoniq5LegacyDeadlineTestFixtureTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(recipe("io.axoniq.framework.migration.Axon4ToAxoniq5LegacyDeadline"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class DeadlineMessageTypeArguments {

        @Test
        void removesTheTypeArgumentsOfTheNonGenericLegacyDeadlineMessages() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.GenericDeadlineMessage;
                            import org.hamcrest.Matcher;

                            import java.util.List;

                            class DeadlineMessages {
                                Matcher<? super DeadlineMessage<?>> matcher;
                                List<DeadlineMessage<String>> triggered;

                                DeadlineMessage<?> create() {
                                    GenericDeadlineMessage<String> message = new GenericDeadlineMessage<>("deadlineName");
                                    return message;
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.GenericDeadlineMessage;
                            import org.hamcrest.Matcher;

                            import java.util.List;

                            class DeadlineMessages {
                                Matcher<? super DeadlineMessage> matcher;
                                List<DeadlineMessage> triggered;

                                DeadlineMessage create() {
                                    GenericDeadlineMessage message = new GenericDeadlineMessage("deadlineName");
                                    return message;
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAUserTypeNamedDeadlineMessageAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            class DeadlineMessage<T> {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            class Deadlines {
                                DeadlineMessage<String> message;
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class FullUpgrade {

        @Test
        void migratesASagaDeadlineTestToTheShapeOfThePortedFixtureTests() {
            // Mirrors the Axon Framework 4 FixtureTest_Deadlines scenarios. The result matches the ported fixture's own
            // deadline tests: the fixture is stopped after each test, and the interceptors take the Axon Framework 5
            // (message, context, chain) shape. The no-op and the unsupported scheduled event assertions are kept and
            // marked rather than removed.
            rewriteRun(
                    spec -> spec.recipe(recipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY),
                    srcTestJava(
                            java(
                                    """
                                    package com.example;

                                    import org.axonframework.deadline.DeadlineManager;
                                    import org.axonframework.deadline.annotation.DeadlineHandler;
                                    import org.axonframework.modelling.saga.SagaEventHandler;
                                    import org.axonframework.modelling.saga.StartSaga;
                                    import org.axonframework.test.saga.SagaTestFixture;
                                    import org.junit.jupiter.api.BeforeEach;
                                    import org.junit.jupiter.api.Test;

                                    import java.time.Duration;
                                    import java.time.Instant;

                                    import static org.axonframework.deadline.GenericDeadlineMessage.asDeadlineMessage;
                                    import static org.axonframework.test.matchers.Matchers.*;

                                    class PaymentSagaTest {

                                        private SagaTestFixture<PaymentSaga> fixture;

                                        @BeforeEach
                                        void setUp() {
                                            fixture = new SagaTestFixture<>(PaymentSaga.class);
                                            fixture.withTransienceCheckDisabled();
                                        }

                                        @Test
                                        void schedulesTheDeadline() {
                                            fixture.givenCurrentTime(Instant.parse("2026-01-01T00:00:00Z"))
                                                   .andThenAPublished(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(1))
                                                   .expectScheduledDeadline(Duration.ofMinutes(9), "details")
                                                   .expectNoScheduledEvents();
                                        }

                                        @Test
                                        void triggersTheDeadline() {
                                            fixture.givenAggregate("p-1")
                                                   .published(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(11))
                                                   .expectTriggeredDeadlinesMatching(payloadsMatching(exactSequenceOf(deepEquals("details"))))
                                                   .expectNoScheduledEvents();
                                        }

                                        @Test
                                        void deadlineDispatchInterceptor() {
                                            fixture.registerDeadlineDispatchInterceptor(
                                                    messages -> (i, m) -> asDeadlineMessage(m.getDeadlineName(), "fake", m.getTimestamp())
                                            )
                                                   .givenAggregate("p-1")
                                                   .published(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(11))
                                                   .expectTriggeredDeadlines("fake");
                                        }

                                        @Test
                                        void deadlineHandlerInterceptor() {
                                            fixture.registerDeadlineHandlerInterceptor((uow, chain) -> {
                                                uow.transformMessage(deadlineMessage -> asDeadlineMessage(
                                                        deadlineMessage.getDeadlineName(), "fake", deadlineMessage.getTimestamp())
                                                );
                                                return chain.proceed();
                                            })
                                                   .givenAggregate("p-1")
                                                   .published(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(11))
                                                   .expectTriggeredDeadlines("fake");
                                        }

                                        static class PaymentStarted {

                                            private final String paymentId;

                                            PaymentStarted(String paymentId) {
                                                this.paymentId = paymentId;
                                            }

                                            public String getPaymentId() {
                                                return paymentId;
                                            }
                                        }

                                        public static class PaymentSaga {

                                            @StartSaga
                                            @SagaEventHandler(associationProperty = "paymentId")
                                            public void on(PaymentStarted event, DeadlineManager deadlineManager) {
                                                deadlineManager.schedule(Duration.ofMinutes(10), "paymentDeadline", "details");
                                            }

                                            @DeadlineHandler(deadlineName = "paymentDeadline")
                                            public void on(String details) {
                                            }
                                        }
                                    }
                                    """,
                                    """
                                    package com.example;

                                    import org.axonframework.deadline.DeadlineManager;
                                    import org.axonframework.deadline.annotation.DeadlineHandler;
                                    import org.axonframework.modelling.saga.SagaEventHandler;
                                    import org.axonframework.modelling.saga.StartSaga;
                                    import org.axonframework.test.saga.SagaTestFixture;
                                    import org.junit.jupiter.api.AfterEach;
                                    import org.junit.jupiter.api.BeforeEach;
                                    import org.junit.jupiter.api.Test;

                                    import java.time.Duration;
                                    import java.time.Instant;

                                    import static org.axonframework.deadline.GenericDeadlineMessage.asDeadlineMessage;
                                    import static org.axonframework.test.matchers.Matchers.*;

                                    class PaymentSagaTest {

                                        private SagaTestFixture<PaymentSaga> fixture;

                                        @BeforeEach
                                        void setUp() {
                                            fixture = new SagaTestFixture<>(PaymentSaga.class);
                                            // TODO(axon4to5): delete this withTransienceCheckDisabled() call. It is a deprecated-for-removal no-op in axoniq-legacy-test: Sagas get no field injection, so there is no transience check to disable.
                                            fixture.withTransienceCheckDisabled();
                                        }

                                        @Test
                                        void schedulesTheDeadline() {
                                            // TODO(axon4to5): remove or replace this scheduled event assertion. axoniq-legacy-test does not port the event scheduler, so expectScheduledEvent*, expectNoScheduledEvent* and expectNoScheduledEvents() throw UnsupportedOperationException.
                                            fixture.givenCurrentTime(Instant.parse("2026-01-01T00:00:00Z"))
                                                   .andThenAPublished(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(1))
                                                   .expectScheduledDeadline(Duration.ofMinutes(9), "details")
                                                   .expectNoScheduledEvents();
                                        }

                                        @Test
                                        void triggersTheDeadline() {
                                            // TODO(axon4to5): remove or replace this scheduled event assertion. axoniq-legacy-test does not port the event scheduler, so expectScheduledEvent*, expectNoScheduledEvent* and expectNoScheduledEvents() throw UnsupportedOperationException.
                                            fixture.givenAggregate("p-1")
                                                   .published(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(11))
                                                   .expectTriggeredDeadlinesMatching(payloadsMatching(exactSequenceOf(deepEquals("details"))))
                                                   .expectNoScheduledEvents();
                                        }

                                        @Test
                                        void deadlineDispatchInterceptor() {
                                            fixture.registerDeadlineDispatchInterceptor(
                                                    (message, context, chain) -> chain.proceed(asDeadlineMessage(message.getDeadlineName(), "fake", message.timestamp()), context)
                                            )
                                                   .givenAggregate("p-1")
                                                   .published(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(11))
                                                   .expectTriggeredDeadlines("fake");
                                        }

                                        @Test
                                        void deadlineHandlerInterceptor() {
                                            fixture.registerDeadlineHandlerInterceptor((message, context, chain) -> chain.proceed(asDeadlineMessage(
                                                        message.getDeadlineName(), "fake", message.timestamp()), context))
                                                   .givenAggregate("p-1")
                                                   .published(new PaymentStarted("p-1"))
                                                   .whenTimeElapses(Duration.ofMinutes(11))
                                                   .expectTriggeredDeadlines("fake");
                                        }

                                        static class PaymentStarted {

                                            private final String paymentId;

                                            PaymentStarted(String paymentId) {
                                                this.paymentId = paymentId;
                                            }

                                            public String getPaymentId() {
                                                return paymentId;
                                            }
                                        }

                                        public static class PaymentSaga {

                                            @StartSaga
                                            @SagaEventHandler(associationProperty = "paymentId")
                                            public void on(PaymentStarted event, DeadlineManager deadlineManager) {
                                                deadlineManager.schedule(Duration.ofMinutes(10), "paymentDeadline", "details");
                                            }

                                            @DeadlineHandler(deadlineName = "paymentDeadline")
                                            public void on(String details) {
                                            }
                                        }

                                        @AfterEach
                                        void tearDown() {
                                            fixture.stop();
                                        }
                                    }
                                    """
                            )
                    )
            );
        }

        @Test
        void migratesATestDrivingTheStubDeadlineManagerDirectly() {
            rewriteRun(
                    spec -> spec.recipe(recipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY),
                    srcTestJava(
                            java(
                                    """
                                    package com.example;

                                    import org.axonframework.deadline.DeadlineMessage;
                                    import org.axonframework.messaging.MetaData;
                                    import org.axonframework.messaging.ScopeDescriptor;
                                    import org.axonframework.test.deadline.ScheduledDeadlineInfo;
                                    import org.axonframework.test.deadline.StubDeadlineManager;
                                    import org.junit.jupiter.api.BeforeEach;
                                    import org.junit.jupiter.api.Test;

                                    import java.time.Duration;
                                    import java.time.Instant;
                                    import java.util.ArrayList;
                                    import java.util.List;

                                    import static org.junit.jupiter.api.Assertions.assertEquals;
                                    import static org.junit.jupiter.api.Assertions.assertTrue;

                                    class ReminderDeadlineTest {

                                        private static final ScopeDescriptor SCOPE = () -> "reminder-scope";

                                        private StubDeadlineManager deadlineManager;

                                        @BeforeEach
                                        void setUp() {
                                            deadlineManager = new StubDeadlineManager(Instant.parse("2026-01-01T00:00:00Z"));
                                        }

                                        @Test
                                        void firesTheDueDeadline() throws Exception {
                                            deadlineManager.registerDispatchInterceptor(
                                                    messages -> (i, m) -> m.andMetaData(MetaData.with("source", "test"))
                                            );
                                            deadlineManager.registerHandlerInterceptor((uow, chain) -> chain.proceed());
                                            deadlineManager.schedule(Duration.ofMinutes(5), "reminder", "payload", SCOPE);

                                            List<DeadlineMessage<?>> fired = new ArrayList<>();
                                            deadlineManager.advanceTimeBy(Duration.ofMinutes(10), (scope, message) -> fired.add(message));

                                            assertEquals(1, fired.size());
                                            assertEquals("payload", fired.get(0).getPayload());
                                            assertEquals("test", fired.get(0).getMetaData().get("source"));
                                            ScheduledDeadlineInfo triggered = deadlineManager.getTriggeredDeadlines().get(0);
                                            assertEquals("reminder", triggered.getDeadlineName());
                                            assertTrue(deadlineManager.getScheduledDeadlines().isEmpty());
                                        }
                                    }
                                    """,
                                    """
                                    package com.example;

                                    import org.axonframework.deadline.DeadlineMessage;
                                    import org.axonframework.messaging.ScopeDescriptor;
                                    import org.axonframework.messaging.core.Metadata;
                                    import org.axonframework.test.deadline.ScheduledDeadlineInfo;
                                    import org.axonframework.test.deadline.StubDeadlineManager;
                                    import org.junit.jupiter.api.BeforeEach;
                                    import org.junit.jupiter.api.Test;

                                    import java.time.Duration;
                                    import java.time.Instant;
                                    import java.util.ArrayList;
                                    import java.util.List;

                                    import static org.junit.jupiter.api.Assertions.assertEquals;
                                    import static org.junit.jupiter.api.Assertions.assertTrue;

                                    class ReminderDeadlineTest {

                                        private static final ScopeDescriptor SCOPE = () -> "reminder-scope";

                                        private StubDeadlineManager deadlineManager;

                                        @BeforeEach
                                        void setUp() {
                                            deadlineManager = new StubDeadlineManager(Instant.parse("2026-01-01T00:00:00Z"));
                                        }

                                        @Test
                                        void firesTheDueDeadline() throws Exception {
                                            deadlineManager.registerDispatchInterceptor(
                                                    (message, context, chain) -> chain.proceed(message.andMetadata(Metadata.with("source", "test")), context)
                                            );
                                            deadlineManager.registerHandlerInterceptor((message, context, chain) -> chain.proceed(message, context));
                                            deadlineManager.schedule(Duration.ofMinutes(5), "reminder", "payload", SCOPE);

                                            List<DeadlineMessage> fired = new ArrayList<>();
                                            deadlineManager.advanceTimeBy(Duration.ofMinutes(10), (scope, message) -> fired.add(message));

                                            assertEquals(1, fired.size());
                                            assertEquals("payload", fired.get(0).payload());
                                            assertEquals("test", fired.get(0).metadata().get("source"));
                                            ScheduledDeadlineInfo triggered = deadlineManager.getTriggeredDeadlines().get(0);
                                            assertEquals("reminder", triggered.getDeadlineName());
                                            assertTrue(deadlineManager.getScheduledDeadlines().isEmpty());
                                        }
                                    }
                                    """
                            )
                    )
            );
        }
    }

    private static Recipe recipe(String name) {
        return Environment.builder()
                          .scanRuntimeClasspath("org.axonframework.migration")
                          .build()
                          .activateRecipes(name);
    }
}
