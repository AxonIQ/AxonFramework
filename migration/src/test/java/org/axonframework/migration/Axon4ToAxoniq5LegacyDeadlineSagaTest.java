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
 * Verifies that the legacy migration moves a Saga's {@code DeadlineManager} field into handler parameters before the
 * Saga migration runs, so the field is not marked as unmigrated resource injection.
 */
class Axon4ToAxoniq5LegacyDeadlineSagaTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("io.axoniq.framework.migration.Axon4ToAxoniq5Legacy"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void movesDeadlineManagerIntoHandlerParametersAndKeepsTheScopeApi() {
        // The deadline recipe runs first, so the closing MarkSagaResourceInjection finds no DeadlineManager field to
        // mark with a TODO. The Axon Framework 4 Scope and ScopeDescriptor usage is kept, as the legacy module keeps it.
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

                        import java.time.Duration;

                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.messaging.Scope;
                        import org.axonframework.messaging.ScopeDescriptor;
                        import org.axonframework.modelling.saga.EndSaga;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.modelling.saga.SagaLifecycle;
                        import org.axonframework.modelling.saga.StartSaga;
                        import org.axonframework.spring.stereotype.Saga;
                        import org.springframework.beans.factory.annotation.Autowired;

                        @Saga
                        public class RentalSaga {
                            @Autowired
                            private transient CommandGateway commandGateway;
                            @Autowired
                            private transient DeadlineManager deadlineManager;
                            private String expiryId;

                            @StartSaga
                            @SagaEventHandler(associationProperty = "rentalId")
                            public void on(String rentalId) {
                                SagaLifecycle.associateWith("paymentId", rentalId);
                                scheduleExpiry(rentalId, Scope.describeCurrentScope());
                            }

                            @SagaEventHandler(associationProperty = "paymentId")
                            public void on(Integer paymentId) {
                                deadlineManager.cancelSchedule("rentalExpired", expiryId);
                                commandGateway.send(paymentId);
                            }

                            @EndSaga
                            @DeadlineHandler(deadlineName = "rentalExpired")
                            public void onExpired(String rentalId) {
                                deadlineManager.cancelAll("reminder");
                            }

                            private void scheduleExpiry(String rentalId, ScopeDescriptor scope) {
                                expiryId = deadlineManager.schedule(Duration.ofDays(1), "rentalExpired", rentalId, scope);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import java.time.Duration;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.messaging.Scope;
                        import org.axonframework.messaging.ScopeDescriptor;
                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.modelling.saga.EndSaga;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.modelling.saga.SagaLifecycle;
                        import org.axonframework.modelling.saga.StartSaga;
                        import org.axonframework.spring.stereotype.Saga;

                        @Saga
                        public class RentalSaga {
                            private String expiryId;

                            @StartSaga
                            @SagaEventHandler(associationProperty = "rentalId")
                            public void on(String rentalId, DeadlineManager deadlineManager, SagaLifecycle sagaLifecycle) {
                                sagaLifecycle.associateWith("paymentId", rentalId);
                                scheduleExpiry(rentalId, Scope.describeCurrentScope(), deadlineManager);
                            }

                            @SagaEventHandler(associationProperty = "paymentId")
                            public void on(Integer paymentId, DeadlineManager deadlineManager, CommandDispatcher commandDispatcher) {
                                deadlineManager.cancelSchedule("rentalExpired", expiryId);
                                commandDispatcher.send(paymentId);
                            }

                            @EndSaga
                            @DeadlineHandler(deadlineName = "rentalExpired")
                            public void onExpired(String rentalId, DeadlineManager deadlineManager) {
                                deadlineManager.cancelAll("reminder");
                            }

                            private void scheduleExpiry(String rentalId, ScopeDescriptor scope, DeadlineManager deadlineManager) {
                                expiryId = deadlineManager.schedule(Duration.ofDays(1), "rentalExpired", rentalId, scope);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void marksTheMovedDeadlineManagerWhenACommandResultCallbackUsesIt() {
        // The marker on the deadline leg survives the Saga leg turning the CommandGateway into a CommandDispatcher.
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

                        import java.time.Duration;

                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.spring.stereotype.Saga;
                        import org.springframework.beans.factory.annotation.Autowired;

                        @Saga
                        public class PaymentSaga {
                            @Autowired
                            private transient CommandGateway commandGateway;
                            @Autowired
                            private transient DeadlineManager deadlineManager;

                            @SagaEventHandler(associationProperty = "paymentId")
                            public void on(String paymentId) {
                                deadlineManager.schedule(Duration.ofMinutes(1), "cancelPayment", paymentId);
                            }

                            @DeadlineHandler(deadlineName = "retryPayment")
                            public void preparePayment(String reference) {
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

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.spring.stereotype.Saga;

                        @Saga
                        public class PaymentSaga {
                            @SagaEventHandler(associationProperty = "paymentId")
                            public void on(String paymentId, DeadlineManager deadlineManager) {
                                deadlineManager.schedule(Duration.ofMinutes(1), "cancelPayment", paymentId);
                            }

                            @DeadlineHandler(deadlineName = "retryPayment")
                            public void preparePayment(String reference, DeadlineManager deadlineManager, CommandDispatcher commandDispatcher) {
                                // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. The \
                        DeadlineManager parameter is bound to the handler: a call from a callback that runs after the \
                        handler returned fails, and the deadline is never scheduled or cancelled. Wait for the result \
                        in the handler, for example with .orTimeout(..).join() or .block(..), and call the \
                        DeadlineManager there.
                                commandDispatcher.send(reference)
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
    void marksTheSubscribedChainThatPassesTheMovedDeadlineManagerToAHelper() {
        // The DeadlineManager argument the field move adds to the helper call has no type attribution, so it is
        // recognized by the name of the handler's new DeadlineManager parameter.
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
                        package reactor.core.publisher;

                        import java.util.function.Function;

                        public abstract class Mono<T> {
                            public abstract <R> Mono<R> flatMap(Function<? super T, ? extends Mono<? extends R>> mapper);
                            public abstract void subscribe();
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        import java.time.Duration;
                        import java.util.function.Function;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import reactor.core.publisher.Mono;

                        public class OrderSaga {
                            @Autowired
                            private transient DeadlineManager deadlineManager;

                            @SagaEventHandler(associationProperty = "orderId")
                            public void on(String orderId, Mono<String> user) {
                                user.flatMap(processPayment(orderId))
                                    .subscribe();
                            }

                            private Function<String, Mono<String>> processPayment(String orderId) {
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
                                // TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. The \
                        DeadlineManager parameter is bound to the handler: a call from a callback that runs after the \
                        handler returned fails, and the deadline is never scheduled or cancelled. Wait for the result \
                        in the handler, for example with .orTimeout(..).join() or .block(..), and call the \
                        DeadlineManager there.
                                user.flatMap(processPayment(orderId, deadlineManager))
                                    .subscribe();
                            }

                            private Function<String, Mono<String>> processPayment(String orderId, DeadlineManager deadlineManager) {
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
    void keepsTheDeadlineMessageFirstWhenBothLegsAddParametersToAParameterlessDeadlineHandler() {
        // The deadline recipe adds the DeadlineMessage with the DeadlineManager, so the ProcessingContext the Saga
        // recipe appends for the conditional end follows them, and neither resolver-ranked parameter is the payload.
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

                        import java.time.Duration;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.modelling.saga.SagaLifecycle;
                        import org.axonframework.modelling.saga.StartSaga;
                        import org.springframework.beans.factory.annotation.Autowired;

                        public class ShipmentSaga {
                            @Autowired
                            private transient DeadlineManager deadlineManager;
                            private int checks;

                            @StartSaga
                            @SagaEventHandler(associationProperty = "orderId")
                            public void on(String orderId) {
                                deadlineManager.schedule(Duration.ofDays(1), "deliveryCheck");
                            }

                            @DeadlineHandler(deadlineName = "deliveryCheck")
                            public void onDeliveryCheck() {
                                if (++checks >= 3) {
                                    SagaLifecycle.end();
                                } else {
                                    deadlineManager.schedule(Duration.ofDays(1), "deliveryCheck");
                                }
                            }
                        }
                        """,
                        """
                        package com.example;

                        import java.time.Duration;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.DeadlineMessage;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.messaging.core.unitofwork.ProcessingContext;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.modelling.saga.SagaLifecycle;
                        import org.axonframework.modelling.saga.StartSaga;

                        public class ShipmentSaga {
                            private int checks;

                            @StartSaga
                            @SagaEventHandler(associationProperty = "orderId")
                            public void on(String orderId, DeadlineManager deadlineManager) {
                                deadlineManager.schedule(Duration.ofDays(1), "deliveryCheck");
                            }

                            @DeadlineHandler(deadlineName = "deliveryCheck")
                            public void onDeliveryCheck(DeadlineMessage deadlineMessage, DeadlineManager deadlineManager, ProcessingContext processingContext) {
                                if (++checks >= 3) {
                                    SagaLifecycle.forContext(processingContext).end();
                                } else {
                                    deadlineManager.schedule(Duration.ofDays(1), "deliveryCheck");
                                }
                            }
                        }
                        """
                )
        );
    }

    @Test
    void marksProtectedDeadlineManagerFieldInsteadOfMovingItAwayFromSubclasses() {
        // OrderSaga uses the inherited field, which the recipe cannot see when migrating BaseSaga. The field stays
        // and gets the resource injection marker, like any other injected Saga field.
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
                        """,
                        """
                        package com.example;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.springframework.beans.factory.annotation.Autowired;

                        public abstract class BaseSaga {
                            // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler or \
                        @DeadlineHandler method that uses it, then remove the field. Axon Framework 5 does not inject \
                        Saga fields. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, \
                        or the dependency becomes its payload.
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

                        public class OrderSaga extends BaseSaga {
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
    void leavesNonInjectedPackagePrivateDeadlineManagerFieldUnmarked() {
        // Axon Framework 4 did not populate a field without an injection annotation either, so nothing changes.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.modelling.saga.SagaEventHandler;

                        public class OrderSaga {
                            transient DeadlineManager deadlineManager;

                            @SagaEventHandler(associationProperty = "orderId")
                            public void on(String event) {
                                deadlineManager.cancelAll("orderExpired");
                            }
                        }
                        """
                )
        );
    }

    @Test
    void movesSetterInjectedCollaboratorsAndMarksTheRemainingInjection() {
        // Axon Framework 4 called the @Autowired setters and set the @Value field; the legacy module does neither.
        // The DeadlineManager and CommandGateway become handler parameters and lose their fields and setters. The
        // setter of the other collaborator and the @Value field stay, marked, so none of them is silently left null.
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

                        import java.time.Duration;

                        import org.axonframework.commandhandling.gateway.CommandGateway;
                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.modelling.saga.StartSaga;
                        import org.axonframework.spring.stereotype.Saga;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Value;

                        @Saga
                        public class OrderSaga {
                            private transient CommandGateway commandGateway;
                            private transient PaymentService paymentService;
                            private transient DeadlineManager deadlineManager;

                            @Value("${app.testing.deadline:false}")
                            private boolean isDeadlineTesting;
                            private String scheduleId;

                            @Autowired
                            public void setCommandGateway(CommandGateway commandGateway) {
                                this.commandGateway = commandGateway;
                            }

                            @Autowired
                            public void setPaymentService(PaymentService paymentService) {
                                this.paymentService = paymentService;
                            }

                            @Autowired
                            public void setDeadlineManager(DeadlineManager deadlineManager) {
                                this.deadlineManager = deadlineManager;
                            }

                            @StartSaga
                            @SagaEventHandler(associationProperty = "orderId")
                            public void handle(String orderId) {
                                scheduleId = deadlineManager.schedule(Duration.ofSeconds(4), "payment-deadline", orderId);
                                if (isDeadlineTesting) return;
                                paymentService.charge(orderId);
                            }

                            @DeadlineHandler(deadlineName = "payment-deadline")
                            public void onPaymentDeadline(String orderId) {
                                deadlineManager.cancelSchedule("payment-deadline", scheduleId);
                                commandGateway.send(orderId);
                            }
                        }
                        interface PaymentService { void charge(String orderId); }
                        """,
                        """
                        package com.example;

                        import java.time.Duration;

                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.annotation.DeadlineHandler;
                        import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                        import org.axonframework.modelling.saga.SagaEventHandler;
                        import org.axonframework.modelling.saga.StartSaga;
                        import org.axonframework.spring.stereotype.Saga;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Value;

                        @Saga
                        public class OrderSaga {
                            private transient PaymentService paymentService;

                            // TODO(axon4to5): read this property in each @SagaEventHandler or @DeadlineHandler method \
                        that uses it, from a Spring Environment parameter or the configuration. Axon Framework 5 does \
                        not inject @Value Saga fields, so the field no longer follows the property.
                            @Value("${app.testing.deadline:false}")
                            private boolean isDeadlineTesting;
                            private String scheduleId;

                            // TODO(axon4to5): add the dependency this method injects as a parameter of each \
                        @SagaEventHandler or @DeadlineHandler method that uses it, then remove this method and the \
                        field it sets. Axon Framework 5 does not call injection methods of a Saga. Put a \
                        DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency \
                        becomes its payload.
                            @Autowired
                            public void setPaymentService(PaymentService paymentService) {
                                this.paymentService = paymentService;
                            }

                            @StartSaga
                            @SagaEventHandler(associationProperty = "orderId")
                            public void handle(String orderId, DeadlineManager deadlineManager) {
                                scheduleId = deadlineManager.schedule(Duration.ofSeconds(4), "payment-deadline", orderId);
                                if (isDeadlineTesting) return;
                                paymentService.charge(orderId);
                            }

                            @DeadlineHandler(deadlineName = "payment-deadline")
                            public void onPaymentDeadline(String orderId, DeadlineManager deadlineManager, CommandDispatcher commandDispatcher) {
                                deadlineManager.cancelSchedule("payment-deadline", scheduleId);
                                commandDispatcher.send(orderId);
                            }
                        }
                        interface PaymentService { void charge(String orderId); }
                        """
                )
        );
    }
}
