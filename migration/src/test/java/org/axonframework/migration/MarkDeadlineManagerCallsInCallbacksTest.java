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

import org.junit.jupiter.api.*;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link MarkDeadlineManagerCallsInCallbacks}.
 *
 * @author Stefan Dragisic
 */
class MarkDeadlineManagerCallsInCallbacksTest implements RewriteTest {

    private static final SourceSpecs MONO = java(
            """
            package reactor.core.publisher;

            import java.util.function.Consumer;
            import java.util.function.Function;

            public abstract class Mono<T> {
                public abstract <R> Mono<R> flatMap(Function<? super T, ? extends Mono<? extends R>> mapper);
                public abstract Mono<T> doOnSuccess(Consumer<? super T> onSuccess);
                public abstract void subscribe();
            }
            """
    );

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkDeadlineManagerCallsInCallbacks())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class CompletionStageCallbacks {

        @Test
        void marksTheHandlerStatementWhoseWhenCompleteCallbackSchedulesADeadline() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            public class PaymentSaga {

                                @DeadlineHandler(deadlineName = "retryPayment")
                                public void preparePayment(String reference, DeadlineManager deadlineManager,
                                                           CommandGateway commandGateway) {
                                    commandGateway.send(reference)
                                                  .whenComplete((result, error) -> {
                                                      if (error != null) {
                                                          deadlineManager.schedule(Duration.ofSeconds(5), "retryPayment", reference);
                                                      }
                                                  });
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            public class PaymentSaga {

                                @DeadlineHandler(deadlineName = "retryPayment")
                                public void preparePayment(String reference, DeadlineManager deadlineManager,
                                                           CommandGateway commandGateway) {
                                    // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. \
                            The DeadlineManager parameter is bound to the handler: a call from a callback that runs \
                            after the handler returned fails, and the deadline is never scheduled or cancelled. Wait \
                            for the result in the handler, for example with .orTimeout(..).join() or .block(..), and \
                            call the DeadlineManager there.
                                    commandGateway.send(reference)
                                                  .whenComplete((result, error) -> {
                                                      if (error != null) {
                                                          deadlineManager.schedule(Duration.ofSeconds(5), "retryPayment", reference);
                                                      }
                                                  });
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksTheHandlerStatementWhoseCallbackPassesTheDeadlineManagerOn() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.util.concurrent.CompletableFuture;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, DeadlineManager deadlineManager) {
                                    CompletableFuture.supplyAsync(() -> orderId)
                                                     .thenAccept(id -> cancelReminders(id, deadlineManager));
                                }

                                private void cancelReminders(String orderId, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder-" + orderId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.util.concurrent.CompletableFuture;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. \
                            The DeadlineManager parameter is bound to the handler: a call from a callback that runs \
                            after the handler returned fails, and the deadline is never scheduled or cancelled. Wait \
                            for the result in the handler, for example with .orTimeout(..).join() or .block(..), and \
                            call the DeadlineManager there.
                                    CompletableFuture.supplyAsync(() -> orderId)
                                                     .thenAccept(id -> cancelReminders(id, deadlineManager));
                                }

                                private void cancelReminders(String orderId, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAll("reminder-" + orderId);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ReactorChains {

        @Test
        void marksTheSubscribedChainWhoseOperatorGetsTheDeadlineManager() {
            rewriteRun(
                    MONO,
                    java(
                            """
                            package com.example;

                            import java.time.Duration;
                            import java.util.function.Function;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import reactor.core.publisher.Mono;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, Mono<String> user, DeadlineManager deadlineManager) {
                                    user.flatMap(processPayment(orderId, deadlineManager))
                                        .subscribe();
                                }

                                private Function<String, Mono<String>> processPayment(String orderId,
                                                                                      DeadlineManager deadlineManager) {
                                    return userId -> {
                                        deadlineManager.schedule(Duration.ofSeconds(10), "payment", orderId);
                                        return null;
                                    };
                                }
                            }
                            """,
                            """
                            package com.example;

                            import java.time.Duration;
                            import java.util.function.Function;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import reactor.core.publisher.Mono;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, Mono<String> user, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. \
                            The DeadlineManager parameter is bound to the handler: a call from a callback that runs \
                            after the handler returned fails, and the deadline is never scheduled or cancelled. Wait \
                            for the result in the handler, for example with .orTimeout(..).join() or .block(..), and \
                            call the DeadlineManager there.
                                    user.flatMap(processPayment(orderId, deadlineManager))
                                        .subscribe();
                                }

                                private Function<String, Mono<String>> processPayment(String orderId,
                                                                                      DeadlineManager deadlineManager) {
                                    return userId -> {
                                        deadlineManager.schedule(Duration.ofSeconds(10), "payment", orderId);
                                        return null;
                                    };
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksTheHandlerStatementWhoseReactorCallbackCancelsADeadline() {
            rewriteRun(
                    MONO,
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import reactor.core.publisher.Mono;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, Mono<String> approval, DeadlineManager deadlineManager) {
                                    approval.doOnSuccess(result -> deadlineManager.cancelAll("orderExpired"))
                                            .subscribe();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import reactor.core.publisher.Mono;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, Mono<String> approval, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. \
                            The DeadlineManager parameter is bound to the handler: a call from a callback that runs \
                            after the handler returned fails, and the deadline is never scheduled or cancelled. Wait \
                            for the result in the handler, for example with .orTimeout(..).join() or .block(..), and \
                            call the DeadlineManager there.
                                    approval.doOnSuccess(result -> deadlineManager.cancelAll("orderExpired"))
                                            .subscribe();
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class SynchronousUsage {

        @Test
        void leavesADeadlineManagerCallInTheHandlerItselfUnmarked() {
            // The callback does not touch the DeadlineManager, and the direct call runs inside the handler.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, DeadlineManager deadlineManager, CommandGateway commandGateway) {
                                    deadlineManager.schedule(Duration.ofDays(1), "orderExpired", orderId);
                                    commandGateway.send(orderId)
                                                  .whenComplete((result, error) -> System.out.println(result));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesADeadlineManagerCallInASynchronousLambdaUnmarked() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.util.List;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, List<String> scheduleIds, DeadlineManager deadlineManager) {
                                    scheduleIds.forEach(id -> deadlineManager.cancelSchedule("orderExpired", id));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesASubscribedChainUnmarkedWhenItsOperatorsOnlyCallTheDeadlineManagerWhileAssembled() {
            // The schedule call in the operator argument runs while the chain is built, inside the handler.
            rewriteRun(
                    MONO,
                    java(
                            """
                            package com.example;

                            import java.time.Duration;
                            import java.util.function.Function;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import reactor.core.publisher.Mono;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, Mono<String> user, DeadlineManager deadlineManager) {
                                    user.flatMap(paymentStep(deadlineManager.schedule(Duration.ofDays(1), "payment")))
                                        .subscribe();
                                }

                                private Function<String, Mono<String>> paymentStep(String scheduleId) {
                                    return userId -> null;
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class OtherClassesAndRepeatedRuns {

        @Test
        void leavesClassesOtherThanSagasUnmarked() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.DeadlineManager;

                            public class ReminderService {

                                public void remind(String orderId, DeadlineManager deadlineManager, CommandGateway commandGateway) {
                                    commandGateway.send(orderId)
                                                  .whenComplete((result, error) -> deadlineManager.schedule(Duration.ofDays(1), "reminder"));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAnAlreadyMarkedStatementUnchanged() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import java.time.Duration;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            public class OrderSaga {

                                @SagaEventHandler(associationProperty = "orderId")
                                public void on(String orderId, DeadlineManager deadlineManager, CommandGateway commandGateway) {
                                    // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. \
                            The DeadlineManager parameter is bound to the handler: a call from a callback that runs \
                            after the handler returned fails, and the deadline is never scheduled or cancelled. Wait \
                            for the result in the handler, for example with .orTimeout(..).join() or .block(..), and \
                            call the DeadlineManager there.
                                    commandGateway.send(orderId)
                                                  .whenComplete((result, error) -> deadlineManager.schedule(Duration.ofDays(1), "retry"));
                                }
                            }
                            """
                    )
            );
        }
    }
}
