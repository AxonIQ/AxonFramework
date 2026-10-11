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
 * Verifies {@link MoveSagaFieldToHandlerParameter} configured for the Axon Framework 4 {@code DeadlineManager}.
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
                                public void onExpired(DeadlineManager deadlineManager) {
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
                                transient DeadlineManager deadlineManager;

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
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            public class RentalSaga {
                                @DeadlineHandler(deadlineName = "rentalExpired")
                                public void onExpired(DeadlineManager deadlineManager) {
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
    class UnmigratableUses {

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
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class RentalSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: String, deadlineManager: DeadlineManager) {
                                    scheduleReminder(event, deadlineManager)
                                }

                                @DeadlineHandler(deadlineName = "reminder")
                                fun onReminder(deadlineManager: DeadlineManager) {
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
