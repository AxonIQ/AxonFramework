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
 * Verifies {@link AddAggregateScopeToDeadlineCalls}: the {@code DeadlineManager} calls of an aggregate that relied on
 * the aggregate scope of the running handler get the {@code AggregateScopeDescriptor} Axon Framework 4 described.
 */
class AddAggregateScopeToDeadlineCallsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddAggregateScopeToDeadlineCalls())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class InstanceHandlersOfAggregates {

        @Test
        void passesScopeWithSimpleClassNameAndIdentifierToEveryScopelessCall() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            import java.time.Duration;
                            import java.time.Instant;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                    deadlineManager.schedule(Instant.now(), "payment", command);
                                    deadlineManager.cancelAllWithinScope("expiry");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;
                            import org.axonframework.modelling.command.AggregateScopeDescriptor;

                            import java.time.Duration;
                            import java.time.Instant;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder", null, new AggregateScopeDescriptor("Order", orderId));
                                    deadlineManager.schedule(Instant.now(), "payment", command, new AggregateScopeDescriptor("Order", orderId));
                                    deadlineManager.cancelAllWithinScope("expiry", new AggregateScopeDescriptor("Order", orderId));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesAggregateTypeAttributeAndQualifiesShadowedIdentifier() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.command.AggregateIdentifier;
                            import org.axonframework.spring.stereotype.Aggregate;

                            import java.time.Duration;

                            @Aggregate(type = "PurchaseOrder")
                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @DeadlineHandler(deadlineName = "reminder")
                                String on(DeadlineManager deadlineManager) {
                                    String orderId = "unrelated";
                                    return deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.command.AggregateIdentifier;
                            import org.axonframework.modelling.command.AggregateScopeDescriptor;
                            import org.axonframework.spring.stereotype.Aggregate;

                            import java.time.Duration;

                            @Aggregate(type = "PurchaseOrder")
                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @DeadlineHandler(deadlineName = "reminder")
                                String on(DeadlineManager deadlineManager) {
                                    String orderId = "unrelated";
                                    return deadlineManager.schedule(Duration.ofDays(1), "reminder", null, new AggregateScopeDescriptor("PurchaseOrder", this.orderId));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesEntityIdOfAnnotatedAggregate() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateRoot;
                            import org.axonframework.modelling.command.EntityId;

                            @AggregateRoot
                            class Order {
                                @EntityId
                                private String orderId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAllWithinScope("expiry");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateRoot;
                            import org.axonframework.modelling.command.AggregateScopeDescriptor;
                            import org.axonframework.modelling.command.EntityId;

                            @AggregateRoot
                            class Order {
                                @EntityId
                                private String orderId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAllWithinScope("expiry", new AggregateScopeDescriptor("Order", orderId));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesRootTypeAndInheritedIdentifierInPolymorphicSubtype() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.command.AggregateIdentifier;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(type = "Vehicle")
                            abstract class Vehicle {
                                @AggregateIdentifier
                                protected String vehicleId;
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.spring.stereotype.Aggregate;

                            import java.time.Duration;

                            @Aggregate
                            class Car extends Vehicle {
                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofHours(1), "service");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateScopeDescriptor;
                            import org.axonframework.spring.stereotype.Aggregate;

                            import java.time.Duration;

                            @Aggregate
                            class Car extends Vehicle {
                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofHours(1), "service", null, new AggregateScopeDescriptor("Vehicle", vehicleId));
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class CallsWithoutReachableIdentifier {

        @Test
        void marksCallInCreationCommandHandlerConstructor() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            import java.time.Duration;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                Order(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            import java.time.Duration;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                Order(String command, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): pass the aggregate scope to this DeadlineManager call, as in new AggregateScopeDescriptor("Order", <aggregate identifier>). Axon Framework 5 runs no aggregate scope around an entity handler, so this call fails without one, and this handler has no aggregate instance to read the identifier from. Take it from the command.
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksStatementHoldingCallInStaticMethod() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            import java.time.Duration;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                static String create(String command, DeadlineManager deadlineManager) {
                                    return deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            import java.time.Duration;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                static String create(String command, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): pass the aggregate scope to this DeadlineManager call, as in new AggregateScopeDescriptor("Order", <aggregate identifier>). Axon Framework 5 runs no aggregate scope around an entity handler, so this call fails without one, and this handler has no aggregate instance to read the identifier from. Take it from the command.
                                    return deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksCallInMemberEntity() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.command.AggregateIdentifier;
                            import org.axonframework.modelling.command.AggregateMember;

                            import java.util.List;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;
                                @AggregateMember
                                private List<OrderLine> lines;
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.EntityId;

                            class OrderLine {
                                @EntityId
                                private String lineId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.cancelAllWithinScope("lineExpiry");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.EntityId;

                            class OrderLine {
                                @EntityId
                                private String lineId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): pass the aggregate scope to this DeadlineManager call, as in new AggregateScopeDescriptor("Order", <aggregate identifier>). Axon Framework 5 runs no aggregate scope around an entity handler, so this call fails without one. Axon Framework 4 used the scope of the aggregate this entity belongs to.
                                    deadlineManager.cancelAllWithinScope("lineExpiry");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksCallInAggregateWithoutIdentifier() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;

                            import java.time.Duration;

                            class Order {
                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;

                            import java.time.Duration;

                            class Order {
                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    // TODO(axon4to5): pass the aggregate scope to this DeadlineManager call, as in new AggregateScopeDescriptor("Order", <aggregate identifier>). Axon Framework 5 runs no aggregate scope around an entity handler, so this call fails without one, and the recipe found no aggregate identifier or type here.
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class CallsLeftAlone {

        @Test
        void leavesExplicitScopeAndScopeIndependentCallsAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.command.AggregateIdentifier;
                            import org.axonframework.modelling.command.AggregateScopeDescriptor;

                            import java.time.Duration;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                void handle(String command, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder", null,
                                                             new AggregateScopeDescriptor("Order", orderId));
                                    deadlineManager.cancelSchedule("reminder", command);
                                    deadlineManager.cancelAll("reminder");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSagaAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            import java.time.Duration;

                            class PaymentSaga {
                                private String orderId;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(String event, DeadlineManager deadlineManager) {
                                    deadlineManager.schedule(Duration.ofDays(1), "paymentDeadline");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesClassThatIsNoAggregateAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;

                            import java.time.Duration;

                            class ReminderService {
                                private final DeadlineManager deadlineManager;

                                ReminderService(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                void remind() {
                                    deadlineManager.schedule(Duration.ofDays(1), "reminder");
                                }
                            }
                            """
                    )
            );
        }
    }
}
