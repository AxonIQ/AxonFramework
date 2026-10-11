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
}
