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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.kotlin.Assertions.srcMainKotlin;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies migration of Axon Framework 4 Sagas onto the {@code io.axoniq.framework:axoniq-legacy} compatibility module.
 */
class Axon4ToAxoniq5LegacySagaTest implements RewriteTest {

    private static final String AXONIQ_VERSION = axoniqVersion();

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("io.axoniq.framework.migration.Axon4ToAxoniq5LegacySaga"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class SagaLifecycleMigration {

        @Test
        void replacesStaticLifecycleCallsWithInjectedParameter() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                    SagaLifecycle.associateWith("paymentId", "payment");
                                    SagaLifecycle.removeAssociationWith("rentalId", "rental");
                                    if (SagaLifecycle.associationValues().isEmpty()) {
                                        SagaLifecycle.end();
                                    }
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, SagaLifecycle sagaLifecycle) {
                                    sagaLifecycle.associateWith("paymentId", "payment");
                                    sagaLifecycle.removeAssociationWith("rentalId", "rental");
                                    if (sagaLifecycle.associationValues().isEmpty()) {
                                        sagaLifecycle.end();
                                    }
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesExistingLifecycleParameter() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, SagaLifecycle lifecycle) {
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, SagaLifecycle lifecycle) {
                                    lifecycle.end();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void appendsTheLifecycleParameterAfterSeveralExistingParameters() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, String rentalId) {
                                    SagaLifecycle.associateWith("rentalId", rentalId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, String rentalId, SagaLifecycle sagaLifecycle) {
                                    sagaLifecycle.associateWith("rentalId", rentalId);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class SagaLifecycleOutsideSagaEventHandler {

        private static final String TODO = "// TODO(axon4to5): replace this static SagaLifecycle call."
                + " axoniq-legacy has no static SagaLifecycle methods and injects a SagaLifecycle parameter into"
                + " @SagaEventHandler methods only. Declare a ProcessingContext parameter, pass it on from every"
                + " handler calling this method, and call SagaLifecycle.forContext(processingContext).";

        @Test
        void replacesAClosingUnconditionalEndInADeadlineHandlerWithEndSaga() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private boolean overdue;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    overdue = true;
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                private boolean overdue;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    overdue = true;
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesAStaticallyImportedEndInASpringSagaDeadlineHandler() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            import static org.axonframework.modelling.saga.SagaLifecycle.end;

                            @Saga
                            class PaymentSaga {
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    // the payment never arrived
                                    end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            class PaymentSaga {
                                @EndSaga
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    // the payment never arrived
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void onlyRemovesTheEndWhenTheDeadlineHandlerAlreadyEndsTheSaga() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                @EndSaga
                                void onOverdue() {
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                @EndSaga
                                void onOverdue() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void callsAConditionalEndInADeadlineHandlerThroughTheProcessingContext() {
            // The ProcessingContext resolver ranks below the payload resolver, so a DeadlineMessage goes first.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private int reminders;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void onReminder() {
                                    if (++reminders > 3) {
                                        SagaLifecycle.end();
                                    }
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private int reminders;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void onReminder(DeadlineMessage deadlineMessage, ProcessingContext processingContext) {
                                    if (++reminders > 3) {
                                        SagaLifecycle.forContext(processingContext).end();
                                    }
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void callsAnEndThatIsNotTheLastStatementOfTheDeadlineHandlerThroughTheProcessingContext() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private boolean overdue;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    SagaLifecycle.end();
                                    overdue = true;
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private boolean overdue;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue(DeadlineMessage deadlineMessage, ProcessingContext processingContext) {
                                    SagaLifecycle.forContext(processingContext).end();
                                    overdue = true;
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void callsEveryLifecycleMethodOfADeadlineHandlerThroughTheProcessingContext() {
            // A handler with a payload parameter keeps it first, so it needs no DeadlineMessage.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue(String id) {
                                    SagaLifecycle.removeAssociationWith("paymentId", id);
                                    if (SagaLifecycle.associationValues().size() == 1) {
                                        SagaLifecycle.associateWith("retryId", id);
                                    }
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue(String id, ProcessingContext processingContext) {
                                    SagaLifecycle.forContext(processingContext).removeAssociationWith("paymentId", id);
                                    if (SagaLifecycle.forContext(processingContext).associationValues().size() == 1) {
                                        SagaLifecycle.forContext(processingContext).associateWith("retryId", id);
                                    }
                                    SagaLifecycle.forContext(processingContext).end();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void reusesAnExistingProcessingContextParameterOfADeadlineHandler() {
            rewriteRun(
                    java(
                            """
                            package org.axonframework.messaging.core.unitofwork;

                            public interface ProcessingContext {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentRetry")
                                void onRetry(String paymentId, ProcessingContext context) {
                                    SagaLifecycle.associateWith("paymentId", paymentId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentRetry")
                                void onRetry(String paymentId, ProcessingContext context) {
                                    SagaLifecycle.forContext(context).associateWith("paymentId", paymentId);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void callsAStaticallyImportedLifecycleMethodOfADeadlineHandlerThroughTheProcessingContext() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            import static org.axonframework.modelling.saga.SagaLifecycle.associateWith;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentRetry")
                                void onRetry(String processingContext) {
                                    String retryId = "retry-" + processingContext;
                                    associateWith("paymentId", retryId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentRetry")
                                void onRetry(String processingContext, ProcessingContext processingContext1) {
                                    String retryId = "retry-" + processingContext;
                                    SagaLifecycle.forContext(processingContext1).associateWith("paymentId", retryId);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksAnEndInAPrivateHelperAndMigratesTheSagaEventHandler() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                    SagaLifecycle.associateWith("paymentId", "payment");
                                    finish();
                                }

                                private void finish() {
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event, SagaLifecycle sagaLifecycle) {
                                    sagaLifecycle.associateWith("paymentId", "payment");
                                    finish();
                                }

                                private void finish() {
                                    %s
                                    SagaLifecycle.end();
                                }
                            }
                            """.formatted(TODO)
                    )
            );
        }

        @Test
        void leavesAnAlreadyMarkedCallUnchanged() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentRetry")
                                void onRetry(String paymentId) {
                                    retry(paymentId);
                                }

                                private void retry(String paymentId) {
                                    %s
                                    SagaLifecycle.associateWith("paymentId", paymentId);
                                }
                            }
                            """.formatted(TODO)
                    )
            );
        }

        @Test
        void leavesStaticLifecycleCallsOutsideASagaAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class SagaSupport {
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    SagaLifecycle.end();
                                }

                                void finish() {
                                    SagaLifecycle.end();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesAClosingEndInAKotlinDeadlineHandlerAndCallsAConditionalOneThroughTheProcessingContext() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                private var reminders = 0

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                fun onOverdue() {
                                    reminders = 0
                                    SagaLifecycle.end()
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                fun onReminder() {
                                    if (++reminders > 3) {
                                        SagaLifecycle.end()
                                    }
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineMessage
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext
                            import org.axonframework.modelling.saga.EndSaga
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                private var reminders = 0

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any) {
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                fun onOverdue() {
                                    reminders = 0
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                fun onReminder(deadlineMessage: DeadlineMessage, processingContext: ProcessingContext) {
                                    if (++reminders > 3) {
                                        SagaLifecycle.forContext(processingContext).end()
                                    }
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void callsAClosingEndThatAnEarlyReturnCanSkipThroughTheProcessingContext() {
            // The paid path never reached the end in Axon Framework 4, while @EndSaga would end the Saga there too.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    if (paid) {
                                        return;
                                    }
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue(DeadlineMessage deadlineMessage, ProcessingContext processingContext) {
                                    if (paid) {
                                        return;
                                    }
                                    SagaLifecycle.forContext(processingContext).end();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesAClosingEndWhenOnlyNestedCodeReturns() {
            // A return in a lambda, an anonymous class or a local class leaves only that nested code.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.util.function.Supplier;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    Supplier<String> reason = () -> {
                                        return "overdue";
                                    };
                                    Supplier<String> anonymous = new Supplier<>() {
                                        @Override
                                        public String get() {
                                            return reason.get();
                                        }
                                    };
                                    class Local {
                                        String reason() {
                                            return anonymous.get();
                                        }
                                    }
                                    new Local().reason();
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.util.function.Supplier;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    Supplier<String> reason = () -> {
                                        return "overdue";
                                    };
                                    Supplier<String> anonymous = new Supplier<>() {
                                        @Override
                                        public String get() {
                                            return reason.get();
                                        }
                                    };
                                    class Local {
                                        String reason() {
                                            return anonymous.get();
                                        }
                                    }
                                    new Local().reason();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesAClosingEndThatAThrowCanSkip() {
            // Axon Framework 4 did not end the Saga on the throwing path. @EndSaga ends it there, but the failed
            // deadline rolls the unit of work back before the Saga is written, so the Saga stays active either way.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    if (paid) {
                                        throw new IllegalStateException("already paid");
                                    }
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.EndSaga;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue() {
                                    if (paid) {
                                        throw new IllegalStateException("already paid");
                                    }
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void callsAClosingEndInAKotlinDeadlineHandlerThatAnEarlyReturnCanSkipThroughTheProcessingContext() {
            // A bare return in an inline lambda returns from the handler, unlike a labelled one.
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                private var paid = false
                                private val payments = listOf(1)

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                fun onOverdue() {
                                    if (paid) {
                                        return
                                    }
                                    SagaLifecycle.end()
                                }

                                @DeadlineHandler(deadlineName = "paymentChecked")
                                fun onChecked() {
                                    payments.forEach { if (it > 0) return }
                                    SagaLifecycle.end()
                                }

                                @DeadlineHandler(deadlineName = "paymentCounted")
                                fun onCounted() {
                                    payments.forEach { if (it > 0) return@forEach }
                                    SagaLifecycle.end()
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineMessage
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext
                            import org.axonframework.modelling.saga.EndSaga
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                private var paid = false
                                private val payments = listOf(1)

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any) {
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                fun onOverdue(deadlineMessage: DeadlineMessage, processingContext: ProcessingContext) {
                                    if (paid) {
                                        return
                                    }
                                    SagaLifecycle.forContext(processingContext).end()
                                }

                                @DeadlineHandler(deadlineName = "paymentChecked")
                                fun onChecked(deadlineMessage: DeadlineMessage, processingContext: ProcessingContext) {
                                    payments.forEach { if (it > 0) return }
                                    SagaLifecycle.forContext(processingContext).end()
                                }

                                @EndSaga
                                @DeadlineHandler(deadlineName = "paymentCounted")
                                fun onCounted() {
                                    payments.forEach { if (it > 0) return@forEach }
                                }
                            }
                            """.formatted(TODO)
                    )
            );
        }
    }

    @Nested
    class CommandDispatchMigration {

        @Test
        void keepsSendFireAndForgetAndSendAndWaitSynchronous() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void prepare(Object command) {
                                    commandGateway.send(command);
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                void confirm(Object command) {
                                    commandGateway.sendAndWait(command);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.FutureUtils;
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void prepare(Object command, CommandDispatcher commandDispatcher) {
                                    commandDispatcher.send(command);
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                void confirm(Object command, CommandDispatcher commandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(command).getResultMessage());
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesRegularEventHandlerToTheGeneralRecipe() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.eventhandling.EventHandler;

                            class Projection {
                                private final CommandGateway commandGateway;

                                Projection(CommandGateway commandGateway) {
                                    this.commandGateway = commandGateway;
                                }

                                @EventHandler
                                void on(Object event) {
                                    commandGateway.sendAndWait(event);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void migratesAGatewayOnlyTheDeadlineHandlersOfASagaUse() {
            // The CommandDispatcher resolver ranks above the payload resolver, so even as the only parameter of a
            // deadline handler the dispatcher does not become the payload, and needs no DeadlineMessage in front.
            rewriteRun(
                    java(
                            """
                            package org.springframework.beans.factory.annotation;

                            public @interface Autowired {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;
                                private String orderId;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(String orderId) {
                                    this.orderId = orderId;
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void onReminder() {
                                    commandGateway.send(orderId);
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue(String reason) {
                                    commandGateway.sendAndWait(reason);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.FutureUtils;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                private String orderId;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(String orderId) {
                                    this.orderId = orderId;
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void onReminder(CommandDispatcher commandDispatcher) {
                                    commandDispatcher.send(orderId);
                                }

                                @DeadlineHandler(deadlineName = "paymentOverdue")
                                void onOverdue(String reason, CommandDispatcher commandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(reason).getResultMessage());
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAGatewayOnlyTheDeadlineHandlersOfANonSagaClassUseAlone() {
            // An aggregate's deadline handler becomes a command handler, which the aggregate recipes migrate.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class PaymentAggregate {
                                private transient CommandGateway commandGateway;

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void onReminder() {
                                    commandGateway.send("remind");
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ResourceInjectionMigration {

        @Test
        void migratesTheGatewayAndMarksTheOtherInjectedField() {
            // The CommandGateway field becomes a handler parameter, so only the remaining injected field is marked.
            // The @Autowired stub resolves the annotation type, as Spring on a real classpath would; unresolved, the
            // gateway migration would drop the import the remaining field still needs.
            rewriteRun(
                    java(
                            """
                            package org.springframework.beans.factory.annotation;

                            public @interface Autowired {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object command) {
                                    paymentService.charge(command);
                                    commandGateway.send(command);
                                }
                            }
                            interface PaymentService { void charge(Object command); }
                            """,
                            """
                            package com.example;

                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object command, CommandDispatcher commandDispatcher) {
                                    paymentService.charge(command);
                                    commandDispatcher.send(command);
                                }
                            }
                            interface PaymentService { void charge(Object command); }
                            """
                    )
            );
        }
    }

    @Nested
    class KotlinSagaMigration {

        @Test
        void migratesLifecycleAndCommandDispatchParameters() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.commandhandling.gateway.CommandGateway
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                private lateinit var commandGateway: CommandGateway

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun prepare(command: Any) {
                                    SagaLifecycle.associateWith("paymentId", "payment")
                                    commandGateway.send(command)
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                fun confirm(command: Any) {
                                    commandGateway.sendAndWait<Any>(command)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.common.FutureUtils
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun prepare(command: Any, sagaLifecycle: SagaLifecycle, commandDispatcher: CommandDispatcher) {
                                    sagaLifecycle.associateWith("paymentId", "payment")
                                    commandDispatcher.send(command)
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                fun confirm(command: Any, commandDispatcher: CommandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(command).getResultMessage())
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void passesTheDispatcherIntoPrivateHelpersThatUseTheGateway() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.commandhandling.gateway.CommandGateway
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class OrderSaga {
                                private lateinit var commandGateway: CommandGateway

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any) {
                                    proceed("o-1")
                                }

                                private fun proceed(orderId: String) {
                                    commandGateway.send(orderId)
                                    compensate(orderId)
                                }

                                private fun compensate(orderId: String) {
                                    commandGateway.sendAndWait<Any>(orderId)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.common.FutureUtils
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class OrderSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any, commandDispatcher: CommandDispatcher) {
                                    proceed("o-1", commandDispatcher)
                                }

                                private fun proceed(orderId: String, commandDispatcher: CommandDispatcher) {
                                    commandDispatcher.send(orderId)
                                    compensate(orderId, commandDispatcher)
                                }

                                private fun compensate(orderId: String, commandDispatcher: CommandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(orderId).getResultMessage())
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class DependencyMigration {

        @Test
        void addsAxoniqLegacyWhenSagaSourceIsPresent() {
            rewriteRun(
                    Axon4ToAxoniq5LegacySagaTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                            </project>
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.axoniq.framework</groupId>
                                        <artifactId>axoniq-legacy</artifactId>
                                        <version>%s</version>
                                    </dependency>
                                </dependencies>
                            </project>
                            """.formatted(AXONIQ_VERSION)
                            ),
                            srcMainJava(
                                    java(
                                            """
                                            package com.example;

                                            import org.axonframework.modelling.saga.SagaEventHandler;

                                            class PaymentSaga {
                                                @SagaEventHandler(associationProperty = "rentalId")
                                                void on(Object event) {
                                                }
                                            }
                                            """
                                    )
                            )
                    )
            );
        }

        @Test
        void addsAxoniqLegacyForKotlinSagaSource() {
            rewriteRun(
                    Axon4ToAxoniq5LegacySagaTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                    </project>
                                    """,
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                        <dependencies>
                                            <dependency>
                                                <groupId>io.axoniq.framework</groupId>
                                                <artifactId>axoniq-legacy</artifactId>
                                                <version>%s</version>
                                            </dependency>
                                        </dependencies>
                                    </project>
                                    """.formatted(AXONIQ_VERSION)
                            ),
                            srcMainKotlin(
                                    kotlin(
                                            """
                                            package com.example

                                            import org.axonframework.spring.stereotype.Saga

                                            @Saga
                                            class PaymentSaga
                                            """
                                    )
                            )
                    )
            );
        }

        @Test
        void doesNotAddAxoniqLegacyWithoutSagaSource() {
            rewriteRun(
                    mavenProject(
                            "rental",
                            pomXml(
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                    </project>
                                    """
                            ),
                            srcMainJava(
                                    java(
                                            """
                                            package com.example;

                                            class Projection {
                                                void on(Object event) {
                                                }
                                            }
                                            """
                                    )
                            )
                    )
            );
        }
    }

    @Nested
    class PrivateHelperMigration {

        @Test
        void passesTheDispatcherIntoPrivateHelpersThatUseTheGateway() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class OrderSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                    paid = true;
                                    proceed("o-1");
                                }

                                @SagaEventHandler(associationProperty = "orderId")
                                void onFailure(Object event) {
                                    compensate("o-1", "failed");
                                }

                                private void proceed(String orderId) {
                                    if (paid) {
                                        commandGateway.send(new Object());
                                    }
                                }

                                private void compensate(String orderId, String reason) {
                                    commandGateway.sendAndWait(new Object());
                                    proceed(orderId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.FutureUtils;
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class OrderSaga {
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event, CommandDispatcher commandDispatcher) {
                                    paid = true;
                                    proceed("o-1", commandDispatcher);
                                }

                                @SagaEventHandler(associationProperty = "orderId")
                                void onFailure(Object event, CommandDispatcher commandDispatcher) {
                                    compensate("o-1", "failed", commandDispatcher);
                                }

                                private void proceed(String orderId, CommandDispatcher commandDispatcher) {
                                    if (paid) {
                                        commandDispatcher.send(new Object());
                                    }
                                }

                                private void compensate(String orderId, String reason, CommandDispatcher commandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(new Object()).getResultMessage());
                                    proceed(orderId, commandDispatcher);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class SagaTestFixtureMigration {

        @Test
        void keepsSagaTestFixtureAddsAxoniqLegacyTestAndATearDown() {
            rewriteRun(
                    Axon4ToAxoniq5LegacySagaTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                            </project>
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.axoniq.framework</groupId>
                                        <artifactId>axoniq-legacy-test</artifactId>
                                        <version>%s</version>
                                        <scope>test</scope>
                                    </dependency>
                                </dependencies>
                            </project>
                            """.formatted(AXONIQ_VERSION)
                            ),
                            srcTestJava(
                                    java(
                                            """
                                            package com.example;

                                            import org.axonframework.test.saga.SagaTestFixture;
                                            import org.junit.jupiter.api.BeforeEach;

                                            class PaymentSagaTest {
                                                private SagaTestFixture<PaymentSaga> fixture;

                                                @BeforeEach
                                                void setUp() {
                                                    fixture = new SagaTestFixture<>(PaymentSaga.class);
                                                }
                                            }
                                            class PaymentSaga {
                                            }
                                            """,
                                            """
                                            package com.example;

                                            import org.axonframework.test.saga.SagaTestFixture;
                                            import org.junit.jupiter.api.AfterEach;
                                            import org.junit.jupiter.api.BeforeEach;

                                            class PaymentSagaTest {
                                                private SagaTestFixture<PaymentSaga> fixture;

                                                @BeforeEach
                                                void setUp() {
                                                    fixture = new SagaTestFixture<>(PaymentSaga.class);
                                                }

                                                @AfterEach
                                                void tearDown() {
                                                    fixture.stop();
                                                }
                                            }
                                            class PaymentSaga {
                                            }
                                            """
                                    )
                            )
                    )
            );
        }
    }

    @Nested
    class TopLevelRecipes {

        private static final String SAGA_POM = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>rental</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        private static final String SAGA_SOURCE = """
                package com.example;

                import org.axonframework.modelling.saga.SagaEventHandler;
                import org.axonframework.modelling.saga.SagaLifecycle;

                class PaymentSaga {
                    @SagaEventHandler(associationProperty = "rentalId")
                    void on(Object event) {
                        SagaLifecycle.end();
                    }
                }
                """;

        @Test
        void freeUpgradeLeavesSagasAndAxoniqLegacyOut() {
            // Sagas only run on the commercial axoniq-legacy module, so the free upgrade must not pull it in.
            rewriteRun(
                    spec -> spec.recipe(topLevelRecipe("org.axonframework.migration.UpgradeAxon4ToAxon5")),
                    mavenProject(
                            "rental",
                            pomXml(SAGA_POM, pom -> pom.after(actual -> {
                                assertThat(actual).doesNotContain("axoniq-legacy");
                                return actual;
                            })),
                            srcMainJava(java(SAGA_SOURCE))
                    )
            );
        }

        @Test
        void commercialUpgradeMigratesSagasOntoAxoniqLegacy() {
            rewriteRun(
                    spec -> spec.recipe(topLevelRecipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY),
                    mavenProject(
                            "rental",
                            pomXml(SAGA_POM, pom -> pom.after(actual -> {
                                assertThat(actual).contains("<artifactId>axoniq-legacy</artifactId>");
                                return actual;
                            })),
                            srcMainJava(java(SAGA_SOURCE, source -> source.after(actual -> {
                                assertThat(actual).contains("void on(Object event, SagaLifecycle sagaLifecycle)");
                                return actual;
                            })))
                    )
            );
        }

        @Test
        void commercialUpgradeKeepsTheTypesOfTheParametersTheLifecycleParameterIsAppendedTo() {
            // The existing parameters must stay attributed, so the free leg still renames MetaData and its
            // annotation, and moves EventMessage while dropping its type argument.
            rewriteRun(
                    spec -> spec.recipe(topLevelRecipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY),
                    mavenProject(
                            "rental",
                            pomXml(SAGA_POM, pom -> pom.after(actual -> actual)),
                            srcMainJava(java(
                                    """
                                    package com.example;

                                    import org.axonframework.eventhandling.EventMessage;
                                    import org.axonframework.messaging.MetaData;
                                    import org.axonframework.messaging.annotation.MetaDataValue;
                                    import org.axonframework.modelling.saga.SagaEventHandler;
                                    import org.axonframework.modelling.saga.SagaLifecycle;

                                    class PaymentSaga {
                                        @SagaEventHandler(associationProperty = "rentalId")
                                        void on(String event, MetaData metaData, @MetaDataValue("k") String k,
                                                EventMessage<?> message) {
                                            SagaLifecycle.end();
                                        }
                                    }
                                    """,
                                    source -> source.after(actual -> {
                                        assertThat(actual)
                                                .contains("import org.axonframework.messaging.core.Metadata;")
                                                .contains("import org.axonframework.messaging.core.annotation"
                                                                  + ".MetadataValue;")
                                                .contains("import org.axonframework.messaging.eventhandling"
                                                                  + ".EventMessage;")
                                                .contains("void on(String event, Metadata metaData, "
                                                                  + "@MetadataValue(\"k\") String k,\n"
                                                                  + "            EventMessage message, "
                                                                  + "SagaLifecycle sagaLifecycle) {")
                                                .doesNotContain("MetaData ")
                                                .doesNotContain("<?>");
                                        return actual;
                                    })
                            ))
                    )
            );
        }

        @Test
        void commercialUpgradeKeepsTheTypesOfTheParametersTheCommandDispatcherIsAppendedTo() {
            rewriteRun(
                    spec -> spec.recipe(topLevelRecipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY),
                    mavenProject(
                            "rental",
                            pomXml(SAGA_POM, pom -> pom.after(actual -> actual)),
                            srcMainJava(java(
                                    """
                                    package org.springframework.beans.factory.annotation;

                                    public @interface Autowired {
                                    }
                                    """
                            )),
                            srcMainJava(java(
                                    """
                                    package com.example;

                                    import org.axonframework.commandhandling.gateway.CommandGateway;
                                    import org.axonframework.eventhandling.EventMessage;
                                    import org.axonframework.messaging.MetaData;
                                    import org.axonframework.messaging.annotation.MetaDataValue;
                                    import org.axonframework.modelling.saga.SagaEventHandler;
                                    import org.springframework.beans.factory.annotation.Autowired;

                                    class PaymentSaga {
                                        @Autowired
                                        private transient CommandGateway commandGateway;

                                        @SagaEventHandler(associationProperty = "rentalId")
                                        void on(String event, MetaData metaData, @MetaDataValue("k") String k,
                                                EventMessage<?> message) {
                                            commandGateway.send(event);
                                        }
                                    }
                                    """,
                                    source -> source.after(actual -> {
                                        assertThat(actual)
                                                .contains("import org.axonframework.messaging.core.Metadata;")
                                                .contains("import org.axonframework.messaging.core.annotation"
                                                                  + ".MetadataValue;")
                                                .contains("import org.axonframework.messaging.eventhandling"
                                                                  + ".EventMessage;")
                                                .contains("void on(String event, Metadata metaData, "
                                                                  + "@MetadataValue(\"k\") String k,\n"
                                                                  + "            EventMessage message, "
                                                                  + "CommandDispatcher commandDispatcher) {")
                                                .doesNotContain("MetaData ")
                                                .doesNotContain("<?>");
                                        return actual;
                                    })
                            ))
                    )
            );
        }

        private static Recipe topLevelRecipe(String name) {
            return Environment.builder()
                              .scanRuntimeClasspath("org.axonframework.migration")
                              .build()
                              .activateRecipes(name);
        }
    }

    private static void ignoreUnpublishedTargetVersionWarning(RecipeSpec spec) {
        // The target may be an unreleased snapshot in CI. Keep asserting the generated coordinate without rendering
        // Maven's download warning as part of the expected POM.
        spec.markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY);
    }

    private static String axoniqVersion() {
        Properties versions = new Properties();
        try (InputStream input = requireNonNull(
                Axon4ToAxoniq5LegacySagaTest.class.getResourceAsStream("/migration-versions.properties")
        )) {
            versions.load(input);
            return versions.getProperty("axoniq.version");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read migration target versions", exception);
        }
    }
}
