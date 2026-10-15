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
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies {@link MoveSagaFieldToHandlerParameter} configured for the Axon Framework 4 {@code DeadlineManager}, and
 * without a type, for the fields injection setters assign.
 */
class MoveSagaFieldToHandlerParameterTest implements RewriteTest {

    private static final SourceSpecs AUTOWIRED = java(
            """
            package org.springframework.beans.factory.annotation;

            public @interface Autowired {
            }
            """
    );
    private static final SourceSpecs JAVAX_INJECT = java(
            """
            package javax.inject;

            public @interface Inject {
            }
            """
    );
    private static final SourceSpecs JAKARTA_INJECT = java(
            """
            package jakarta.inject;

            public @interface Inject {
            }
            """
    );

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MoveSagaFieldToHandlerParameter("org.axonframework.deadline.DeadlineManager"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class HandlerParameters {

        @Test
        void movesAutowiredFieldIntoSagaEventAndDeadlineHandlers() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.StartSaga;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                // Injected by Axon Framework 4.
                                @Autowired
                                private transient DeadlineManager deadlineManager;
                                private String deadlineId;

                                @StartSaga
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineId = deadlineManager.schedule(Duration.ofDays(1), "rentalExpired");
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired() {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.StartSaga;

                            public class RentalSaga {
                                private String deadlineId;

                                @StartSaga
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager deadlineManager) {
                                    deadlineId = deadlineManager.schedule(Duration.ofDays(1), "rentalExpired");
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired(DeadlineMessage deadlineMessage, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void movesJavaxInjectField() {
            rewriteRun(
                    JAVAX_INJECT,
                    java(
                            """
                            package com.example;

                            import javax.inject.Inject;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @Inject
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAllWithinScope("rentalExpired");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAllWithinScope("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void movesJakartaInjectFieldOfSpringSagaStereotype() {
            rewriteRun(
                    JAKARTA_INJECT,
                    java(
                            """
                            package com.example;

                            import jakarta.inject.Inject;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            public class RentalSaga {
                                @Inject
                                private transient DeadlineManager deadlineManager;

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired() {
                                    deadlineManager.cancelSchedule("reminder", "r-1");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            public class RentalSaga {
                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired(DeadlineMessage deadlineMessage, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelSchedule("reminder", "r-1");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void reusesAnExistingParameterOfTheType() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager manager) {
                                    this.deadlineManager.cancelAll("rentalExpired");
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager manager) {
                                    manager.cancelAll("rentalExpired");
                                    manager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void movesFieldUsedInsideLambdaAndAsArgument() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import java.util.List;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(List<String> deadlineIds) {
                                    deadlineIds.forEach(id -> deadlineManager.cancelSchedule("reminder", id));
                                    Reminders.cancel(deadlineManager);
                                }
                            }

                            class Reminders {
                                static void cancel(DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.util.List;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(List<String> deadlineIds, DeadlineManager deadlineManager) {
                                    deadlineIds.forEach(id -> deadlineManager.cancelSchedule("reminder", id));
                                    Reminders.cancel(deadlineManager);
                                }
                            }

                            class Reminders {
                                static void cancel(DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void removesFieldNoMethodUses() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void suffixesTheParameterWhenTheFieldNameIsTaken() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String deadlineManager) {
                                    this.deadlineManager.cancelAll(deadlineManager);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String deadlineManager, DeadlineManager deadlineManager1) {
                                    deadlineManager1.cancelAll(deadlineManager);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ParameterlessDeadlineHandlers {

        @Test
        void prependsDeadlineMessageSoTheMovedParameterDoesNotBecomeThePayload() {
            // The first parameter of a handler is its payload unless a resolver of a higher priority claims it. The
            // configuration resolver ranks lower, so a lone DeadlineManager parameter would only match deadlines
            // carrying a DeadlineManager payload. DeadlineMessage keeps matching any deadline, as no parameter did.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired() {
                                    deadlineManager.cancelAll("reminder");
                                }

                                @DeadlineHandler(deadlineName = "reminder")
                                public void onReminder(String reminder) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired(DeadlineMessage deadlineMessage, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder");
                                }

                                @DeadlineHandler(deadlineName = "reminder")
                                public void onReminder(String reminder, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void suffixesTheDeadlineMessageParameterWhenItsNameIsTaken() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired() {
                                    String deadlineMessage = "expired";
                                    deadlineManager.cancelAll(deadlineMessage);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired(DeadlineMessage deadlineMessage1, DeadlineManager deadlineManager) {
                                    String deadlineMessage = "expired";
                                    deadlineManager.cancelAll(deadlineMessage);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenAnotherMethodCallsTheParameterlessDeadlineHandler() {
            // The handler would gain two parameters, while its call site can only pass the moved one along.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    onExpired();
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired() {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void prependsDeadlineMessageInKotlin() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            class RentalSaga {
                                @Autowired
                                @Transient
                                private lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                fun onExpired() {
                                    deadlineManager.cancelAll("reminder")
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.deadline.DeadlineMessage
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                }

                                @DeadlineHandler(deadlineName = "rentalExpired")
                                fun onExpired(deadlineMessage: DeadlineMessage, deadlineManager: DeadlineManager) {
                                    deadlineManager.cancelAll("reminder")
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class PrivateHelpers {

        @Test
        void passesTheParameterIntoPrivateHelpers() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    scheduleReminder();
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(Integer event) {
                                    reschedule(event.toString());
                                }

                                private void scheduleReminder() {
                                    deadlineManager.schedule(Duration.ofHours(1), "reminder");
                                }

                                private void reschedule(String id) {
                                    deadlineManager.cancelSchedule("reminder", id);
                                    scheduleReminder();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager deadlineManager) {
                                    scheduleReminder(deadlineManager);
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(Integer event, DeadlineManager deadlineManager) {
                                    reschedule(event.toString(), deadlineManager);
                                }

                                private void scheduleReminder(DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofHours(1), "reminder");
                                }

                                private void reschedule(String id, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelSchedule("reminder", id);
                                    scheduleReminder(deadlineManager);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void passesTheExistingHandlerParameterIntoHelpers() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager manager) {
                                    cancelReminders();
                                }

                                private void cancelReminders() {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager manager) {
                                    cancelReminders(manager);
                                }

                                private void cancelReminders(DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class InjectionSetters {

        @Test
        void movesSetterInjectedFieldAndRemovesTheSetter() {
            // The other injected setter keeps its field and the @Autowired import; MarkSagaResourceInjection marks it.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Autowired;

                            @Saga
                            public class OrderSaga {
                                private transient Object queryGateway;
                                private transient DeadlineManager deadlineManager;
                                private String scheduleId;

                                @Autowired
                                public void setDeadlineManager(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }

                                @SagaEventHandler(associationProperty = "orderId")
                                public void handle(String event) {
                                    scheduleId = deadlineManager.schedule(Duration.ofSeconds(4), "payment-deadline", event);
                                }

                                @DeadlineHandler(deadlineName = "payment-deadline")
                                public void onPaymentDeadline(String event) {
                                    cancelDeadline();
                                }

                                private void cancelDeadline() {
                                    deadlineManager.cancelSchedule("payment-deadline", scheduleId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Autowired;

                            @Saga
                            public class OrderSaga {
                                private transient Object queryGateway;
                                private String scheduleId;

                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }

                                @SagaEventHandler(associationProperty = "orderId")
                                public void handle(String event, DeadlineManager deadlineManager) {
                                    scheduleId = deadlineManager.schedule(Duration.ofSeconds(4), "payment-deadline", event);
                                }

                                @DeadlineHandler(deadlineName = "payment-deadline")
                                public void onPaymentDeadline(String event, DeadlineManager deadlineManager) {
                                    cancelDeadline(deadlineManager);
                                }

                                private void cancelDeadline(DeadlineManager deadlineManager) {
                                    deadlineManager.cancelSchedule("payment-deadline", scheduleId);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void movesFieldAnInjectSetterAssignsWithoutThis() {
            rewriteRun(
                    JAVAX_INJECT,
                    java(
                            """
                            package com.example;

                            import javax.inject.Inject;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                private DeadlineManager deadlineManager;

                                @Inject
                                void setDeadlineManager(DeadlineManager manager) {
                                    deadlineManager = manager;
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenASetterWithoutInjectionAssignsIt() {
            // Axon Framework 4 did not call this setter, so whatever calls it still needs the field.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                private transient DeadlineManager deadlineManager;

                                public void setDeadlineManager(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenTheInjectionSetterDoesMoreThanAssignIt() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                private transient DeadlineManager deadlineManager;

                                @Autowired
                                public void setDeadlineManager(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                    deadlineManager.cancelAll("stale");
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenAnotherMethodCallsTheInjectionSetter() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                private transient DeadlineManager deadlineManager;

                                @Autowired
                                public void setDeadlineManager(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }

                                private void replace(DeadlineManager replacement) {
                                    setDeadlineManager(replacement);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class SetterInjectedFieldsOfAnyType implements RewriteTest {

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new MoveSagaFieldToHandlerParameter(null))
                .typeValidationOptions(TypeValidation.none());
        }

        @Test
        void movesEverySetterInjectedFieldIntoTheHandlersAndHelpersThatUseIt() {
            // The fields move one after the other, so a handler using both gets both parameters, in field order.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                private transient PaymentService paymentService;
                                private transient Notifier notifier;
                                private String paymentId;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @Autowired
                                public void setNotifier(Notifier notifier) {
                                    this.notifier = notifier;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentId = paymentService.charge(event);
                                    notifyCustomer(event);
                                }

                                @DeadlineHandler(deadlineName = "payment-overdue")
                                public void onOverdue() {
                                    paymentService.refund(paymentId);
                                }

                                private void notifyCustomer(String event) {
                                    notifier.send(event);
                                }
                            }
                            interface PaymentService { String charge(String event); void refund(String paymentId); }
                            interface Notifier { void send(String event); }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class PaymentSaga {
                                private String paymentId;

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event, PaymentService paymentService, Notifier notifier) {
                                    paymentId = paymentService.charge(event);
                                    notifyCustomer(event, notifier);
                                }

                                @DeadlineHandler(deadlineName = "payment-overdue")
                                public void onOverdue(DeadlineMessage deadlineMessage, PaymentService paymentService) {
                                    paymentService.refund(paymentId);
                                }

                                private void notifyCustomer(String event, Notifier notifier) {
                                    notifier.send(event);
                                }
                            }
                            interface PaymentService { String charge(String event); void refund(String paymentId); }
                            interface Notifier { void send(String event); }
                            """
                    )
            );
        }

        @Test
        void declaresTheParameterLikeTheSetterParameterUnderTheFieldName() {
            // A @Qualifier on the parameter selects the same Spring bean for the handler parameter.
            rewriteRun(
                    JAVAX_INJECT,
                    java(
                            """
                            package org.springframework.beans.factory.annotation;

                            public @interface Qualifier {
                                String value() default "";
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import java.util.List;

                            import javax.inject.Inject;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Qualifier;

                            public class PaymentSaga {
                                private List<PaymentService> paymentServices;

                                @Inject
                                void setPaymentServices(@Qualifier("card") List<PaymentService> services) {
                                    paymentServices = services;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentServices.forEach(service -> service.charge(event));
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """,
                            """
                            package com.example;

                            import java.util.List;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Qualifier;

                            public class PaymentSaga {
                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event, @Qualifier("card") List<PaymentService> paymentServices) {
                                    paymentServices.forEach(service -> service.charge(event));
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void addsAParameterEvenWhenTheHandlerDeclaresOneOfTheSameType() {
            // The payload shares the field's type here, but holds the event rather than the injected collaborator.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class AuditSaga {
                                private Object auditLog;

                                @Autowired
                                public void setAuditLog(Object auditLog) {
                                    this.auditLog = auditLog;
                                }

                                @SagaEventHandler(associationProperty = "auditId")
                                public void on(Object event) {
                                    System.out.println(auditLog + " " + event);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class AuditSaga {
                                @SagaEventHandler(associationProperty = "auditId")
                                public void on(Object event, Object auditLog) {
                                    System.out.println(auditLog + " " + event);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSetterInjectedFieldNoMethodUsesInPlace() {
            // MarkSagaResourceInjection marks the setter instead.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                private transient PaymentService paymentService;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void leavesInjectedFieldWithoutSetterInPlace() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentService.charge(event);
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void leavesFieldsWhoseSetterReceivedADifferentValueThanAHandlerParameterInPlace() {
            // An optional dependency, a qualified bean, a property and an interface method do not move into a handler
            // parameter that receives any bean of the type.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;
                            import org.springframework.beans.factory.annotation.Qualifier;
                            import org.springframework.beans.factory.annotation.Value;

                            public class PaymentSaga implements AuditAware {
                                private transient PaymentService paymentService;
                                private transient PaymentService refundService;
                                private String currency;
                                private transient Object auditLog;

                                @Autowired(required = false)
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @Autowired
                                @Qualifier("refunds")
                                public void setRefundService(PaymentService refundService) {
                                    this.refundService = refundService;
                                }

                                @Autowired
                                public void setCurrency(@Value("${payment.currency}") String currency) {
                                    this.currency = currency;
                                }

                                @Autowired
                                @Override
                                public void setAuditLog(Object auditLog) {
                                    this.auditLog = auditLog;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentService.charge(event + currency + auditLog);
                                    refundService.charge(event);
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            interface AuditAware { void setAuditLog(Object auditLog); }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenAnotherMethodCallsTheSetter() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                private transient PaymentService paymentService;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentService.charge(event);
                                }

                                private void replace(PaymentService replacement) {
                                    setPaymentService(replacement);
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenTheSetterDoesMoreThanAssignIt() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                private transient PaymentService paymentService;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                    paymentService.charge("setup");
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentService.charge(event);
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void leavesFieldASubclassMayUseUntouched() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                protected transient PaymentService paymentService;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentService.charge(event);
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUsedInPublicNonHandlerMethodUntouched() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class PaymentSaga {
                                private transient PaymentService paymentService;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                public void on(String event) {
                                    paymentService.charge(event);
                                }

                                public void retry(String event) {
                                    paymentService.charge(event);
                                }
                            }
                            interface PaymentService { void charge(String event); }
                            """
                    )
            );
        }

        @Test
        void movesSetterInjectedKotlinProperty() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            class PaymentSaga {
                                private lateinit var paymentService: PaymentService

                                @Autowired
                                fun setPaymentService(service: PaymentService) {
                                    paymentService = service
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                fun on(event: String) {
                                    paymentService.charge(event)
                                }
                            }

                            interface PaymentService {
                                fun charge(event: String)
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.modelling.saga.SagaEventHandler

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "paymentId")
                                fun on(event: String, paymentService: PaymentService) {
                                    paymentService.charge(event)
                                }
                            }

                            interface PaymentService {
                                fun charge(event: String)
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class UnmigratableUses {

        @Test
        void leavesProtectedFieldASubclassMayUseUntouched() {
            // The subclass uses the inherited field, so removing it from the base Saga would break the subclass.
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public abstract class BaseSaga {
                                @Autowired
                                protected transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("orderExpired");
                                }
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class OrderSaga extends BaseSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(Integer event) {
                                }

                                @DeadlineHandler(deadlineName = "orderExpired")
                                public void onExpired() {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesPackagePrivateFieldUntouched() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUsedInPublicNonHandlerMethodUntouched() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }

                                public void cancelReminders() {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenAPublicMethodCallsAnAffectedHelper() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    cancelReminders();
                                }

                                public void reset() {
                                    cancelReminders();
                                }

                                private void cancelReminders() {
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesConstructorInjectedFieldUntouched() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class RentalSaga {
                                private final transient DeadlineManager deadlineManager;

                                public RentalSaga(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUntouchedWhenAnAffectedHelperIsUsedAsMethodReference() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import java.util.List;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(List<String> deadlineIds) {
                                    deadlineIds.forEach(this::cancel);
                                }

                                private void cancel(String deadlineId) {
                                    deadlineManager.cancelSchedule("reminder", deadlineId);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUsedInAnonymousClassUntouched() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    Runnable cancel = new Runnable() {
                                        @Override
                                        public void run() {
                                            deadlineManager.cancelAll("rentalExpired");
                                        }
                                    };
                                    cancel.run();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesFieldUsedInFieldInitializerUntouched() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import java.util.function.Supplier;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;
                                private final transient Supplier<DeadlineManager> manager = () -> deadlineManager;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class OtherClasses {

        @Test
        void leavesAggregateWithDeadlineManagerFieldAlone() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class Rental {
                                @Autowired
                                private transient DeadlineManager deadlineManager;

                                @CommandHandler
                                public void handle(String command) {
                                    deadlineManager.cancelAll("rentalExpired");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSagaWithoutTheFieldAlone() {
            rewriteRun(
                    AUTOWIRED,
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient Object paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    paymentService.toString();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsTheAnnotationImportAnotherInjectedFieldStillNeeds() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient DeadlineManager deadlineManager;
                                @Autowired
                                private transient Object paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event) {
                                    deadlineManager.cancelAll(paymentService.toString());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            public class RentalSaga {
                                @Autowired
                                private transient Object paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                public void on(String event, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll(paymentService.toString());
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class KotlinSagas {

        @Test
        void movesLateinitPropertyIntoHandlerAndHelperParameters() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import java.time.Duration
                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            class RentalSaga {
                                @Autowired
                                @Transient
                                private lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                    scheduleReminder(event)
                                }

                                @DeadlineHandler(deadlineName = "reminder")
                                fun onReminder() {
                                    deadlineManager.cancelAll("rentalExpired")
                                }

                                private fun scheduleReminder(id: String) {
                                    deadlineManager.schedule(Duration.ofHours(1), id)
                                }
                            }
                            """,
                            """
                            package com.example

                            import java.time.Duration
                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.deadline.DeadlineMessage
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String, deadlineManager: DeadlineManager) {
                                    scheduleReminder(event, deadlineManager)
                                }

                                @DeadlineHandler(deadlineName = "reminder")
                                fun onReminder(deadlineMessage: DeadlineMessage, deadlineManager: DeadlineManager) {
                                    deadlineManager.cancelAll("rentalExpired")
                                }

                                private fun scheduleReminder(id: String, deadlineManager: DeadlineManager) {
                                    deadlineManager.schedule(Duration.ofHours(1), id)
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void movesPublicPropertyOfAFinalClass() {
            // A Kotlin class is final unless declared open or abstract, so no subclass can use the property.
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            class RentalSaga {
                                @Autowired
                                lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                    deadlineManager.cancelAll(event)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String, deadlineManager: DeadlineManager) {
                                    deadlineManager.cancelAll(event)
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesNonPrivatePropertyOfAnOpenOrAbstractClassUntouched() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            open class RentalSaga {
                                @Autowired
                                lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                    deadlineManager.cancelAll(event)
                                }
                            }
                            """
                    ),
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            abstract class BaseSaga {
                                @Autowired
                                protected lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: String) {
                                    deadlineManager.cancelAll(event)
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void movesPrivatePropertyOfAnOpenClass() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler

                            open class RentalSaga {
                                private lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                    deadlineManager.cancelAll(event)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler

                            open class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String, deadlineManager: DeadlineManager) {
                                    deadlineManager.cancelAll(event)
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesPropertyUsedInPublicNonHandlerFunctionUntouched() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class RentalSaga {
                                private lateinit var deadlineManager: DeadlineManager

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String) {
                                    deadlineManager.cancelAll(event)
                                }

                                fun cancelReminders() {
                                    deadlineManager.cancelAll("reminder")
                                }
                            }
                            """
                    )
            );
        }
    }
}
