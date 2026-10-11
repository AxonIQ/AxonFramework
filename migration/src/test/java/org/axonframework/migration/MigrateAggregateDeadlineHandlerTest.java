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
 * Verifies {@link MigrateAggregateDeadlineHandler}: an aggregate's {@code @DeadlineHandler} becomes the
 * {@code @CommandHandler} that receives the command a fired deadline is translated into, while Sagas keep theirs.
 */
class MigrateAggregateDeadlineHandlerTest implements RewriteTest {

    private static final String PAYLOAD = """
            package com.example;

            public class PaymentDeadline {
                private final String orderId;

                public PaymentDeadline(String orderId) {
                    this.orderId = orderId;
                }
            }
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAggregateDeadlineHandler())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class HandlerWithPayload {

        @Test
        void replacesDeadlineHandlerWithPlainCommandHandlerAndLeavesPayloadClassAlone() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.modelling.command.AggregateIdentifier;

                            class Order {
                                @AggregateIdentifier
                                private String orderId;

                                @CommandHandler
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesDeadlineHandlerWithoutAttributes() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler
                                void on(PaymentDeadline deadline, String unused) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                @CommandHandler
                                void on(PaymentDeadline deadline, String unused) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesDeadlineHandlerOfMemberEntity() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.command.EntityId;

                            class OrderLine {
                                @EntityId
                                private String lineId;

                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.modelling.command.EntityId;

                            class OrderLine {
                                @EntityId
                                private String lineId;

                                @CommandHandler
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksBothHandlersThatReceiveTheSamePayloadType() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "firstReminder")
                                void onFirst(PaymentDeadline deadline) {
                                }

                                @DeadlineHandler(deadlineName = "secondReminder")
                                void onSecond(PaymentDeadline deadline) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                // TODO(axon4to5): give each deadline handled here a payload class of its own. Another handler of this class receives the same payload type, which Axon Framework 4 told apart by deadline name or message type. A fired aggregate deadline now arrives as a command named after its payload class, or after the deadline name when it has no payload.
                                @CommandHandler
                                void onFirst(PaymentDeadline deadline) {
                                }

                                // TODO(axon4to5): give each deadline handled here a payload class of its own. Another handler of this class receives the same payload type, which Axon Framework 4 told apart by deadline name or message type. A fired aggregate deadline now arrives as a command named after its payload class, or after the deadline name when it has no payload.
                                @CommandHandler
                                void onSecond(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksHandlerWhosePayloadTypeAnExistingCommandHandlerReceives() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @CommandHandler
                                void handle(PaymentDeadline command) {
                                }

                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                @CommandHandler
                                void handle(PaymentDeadline command) {
                                }

                                // TODO(axon4to5): give each deadline handled here a payload class of its own. Another handler of this class receives the same payload type, which Axon Framework 4 told apart by deadline name or message type. A fired aggregate deadline now arrives as a command named after its payload class, or after the deadline name when it has no payload.
                                @CommandHandler
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksDeadlineMessageParameter() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(PaymentDeadline deadline, DeadlineMessage<?> message) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.deadline.DeadlineMessage;

                            class Order {
                                // TODO(axon4to5): replace the DeadlineMessage parameter with the payload or metadata this handler needs. A fired aggregate deadline now arrives as a command message, which this parameter does not match.
                                @CommandHandler
                                void on(PaymentDeadline deadline, DeadlineMessage<?> message) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksObjectPayloadParameterAndKeepsDeadlineHandler() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(Object payload) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler for a concrete payload class. Its payload type names no command. A fired aggregate deadline now arrives as a command named after its payload class, or after the deadline name when it has no payload.
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(Object payload) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksInterfacePayloadParameterAndKeepsDeadlineHandler() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            public interface Reminder {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler
                                void on(Reminder reminder) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler for a concrete payload class. Its payload type names no command. A fired aggregate deadline now arrives as a command named after its payload class, or after the deadline name when it has no payload.
                                @DeadlineHandler
                                void on(Reminder reminder) {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class HandlerWithoutPayload {

        @Test
        void routesHandlerByDeadlineNameAsCommandName() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                @CommandHandler(commandName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsDeadlineNameConstantAsCommandName() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.messaging.annotation.MetaDataValue;

                            class Order {
                                static final String PAYMENT_DEADLINE = "paymentDeadline";

                                @DeadlineHandler(deadlineName = PAYMENT_DEADLINE)
                                void on(@MetaDataValue("origin") String origin) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.messaging.annotation.MetaDataValue;

                            class Order {
                                static final String PAYMENT_DEADLINE = "paymentDeadline";

                                @CommandHandler(commandName = PAYMENT_DEADLINE)
                                void on(@MetaDataValue("origin") String origin) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsPayloadTypeAndDropsDeadlineName() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline", payloadType = PaymentDeadline.class)
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                @CommandHandler(payloadType = PaymentDeadline.class)
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksHandlerThatNamesNeitherDeadlineNorPayloadType() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler
                                void onAnyDeadline() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler per deadline it handles. It names neither a deadline nor a payload type, so it handled every deadline of the aggregate. A fired aggregate deadline now arrives as a command named after its payload class, or after the deadline name when it has no payload.
                                @DeadlineHandler
                                void onAnyDeadline() {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class Sagas {

        @Test
        void leavesSagaDeadlineHandlerAlone() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(String event) {
                                }

                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSpringSagaStereotypeAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            class PaymentSaga {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSagaBaseClassAndSubclassAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            abstract class AbstractPaymentSaga {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga extends AbstractPaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                void on(String event) {
                                }
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class ExpressPaymentSaga extends PaymentSaga {
                                @DeadlineHandler(deadlineName = "expressDeadline")
                                void onExpress() {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class Idempotency {

        @Test
        void leavesMigratedHandlerAlone() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                @CommandHandler(commandName = "reminder")
                                void onReminder() {
                                }

                                @CommandHandler
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }
    }
}
