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
import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies {@link MigrateAggregateDeadlineHandler}: an aggregate's {@code @DeadlineHandler} becomes the
 * {@code @CommandHandler} that receives the command a fired deadline is translated into, named after the aggregate
 * type and the deadline, while Sagas keep theirs.
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
    class HandlerWithDeadlineName {

        @Test
        void replacesDeadlineHandlerWithCommandHandlerNamedAfterAggregateTypeAndDeadline() {
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

                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesDeadlineHandlerWithoutPayload() {
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
                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void resolvesDeadlineNameConstant() {
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

                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on(@MetaDataValue("origin") String origin) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsPayloadTypeAndReplacesDeadlineNameWithCommandName() {
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
                                @CommandHandler(commandName = "Order.paymentDeadline", payloadType = PaymentDeadline.class)
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void separatesDeadlinesThatShareAPayloadType() {
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
                                @CommandHandler(commandName = "Order.firstReminder")
                                void onFirst(PaymentDeadline deadline) {
                                }

                                @CommandHandler(commandName = "Order.secondReminder")
                                void onSecond(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesHandlerAlongsideCommandHandlerForItsPayloadType() {
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

                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesHandlerWithObjectPayloadParameter() {
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

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on(Object payload) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksBothHandlersThatReceiveTheSameDeadline() {
            // One deadline name scheduled with several payload types, which Axon Framework 4 told apart by type
            rewriteRun(
                    java(
                            """
                            package com.example;

                            public record FirstReminder(String orderId) {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            public record FinalReminder(String orderId) {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void on(FirstReminder reminder) {
                                }

                                @DeadlineHandler(deadlineName = "paymentReminder")
                                void on(FinalReminder reminder) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                // TODO(axon4to5): merge the handlers of this deadline into one handler. Another handler of this class receives the same deadline under the same command name, but you can only declare one handler per command name, so the application will fail to start until they are merged.
                                @CommandHandler(commandName = "Order.paymentReminder")
                                void on(FirstReminder reminder) {
                                }

                                // TODO(axon4to5): merge the handlers of this deadline into one handler. Another handler of this class receives the same deadline under the same command name, but you can only declare one handler per command name, so the application will fail to start until they are merged.
                                @CommandHandler(commandName = "Order.paymentReminder")
                                void on(FinalReminder reminder) {
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
                                // TODO(axon4to5): replace the DeadlineMessage parameter with the payload or metadata this handler needs. A fired aggregate deadline now arrives as a command message, which the DeadlineMessage parameter does not match.
                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on(PaymentDeadline deadline, DeadlineMessage<?> message) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksHandlerWhoseDeadlineNameCannotBeResolved() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = Deadlines.PAYMENT)
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler(commandName = "<aggregate type>.<deadline name>"). The recipe could not resolve its deadline name or aggregate type. A fired aggregate deadline now arrives as a command named <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its @AggregateRoot or @Aggregate annotation, or its simple class name.
                                @DeadlineHandler(deadlineName = Deadlines.PAYMENT)
                                void on() {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class HandlerWithoutDeadlineName {

        @Test
        void namesHandlerAfterItsPayloadTypeAndMarksIt() {
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
                                // TODO(axon4to5): the original @DeadlineHandler did not name the deadline explicitly. Name the deadline this @CommandHandler handles with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") after the name this deadline was scheduled with. Until you name the deadline, the legacy deadline support will fire the deadline named after its payload type with a warning.
                                @CommandHandler
                                void on(PaymentDeadline deadline, String unused) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsPayloadTypeAttributeOfHandlerWithoutDeadlineName() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(payloadType = PaymentDeadline.class)
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                // TODO(axon4to5): the original @DeadlineHandler did not name the deadline explicitly. Name the deadline this @CommandHandler handles with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") after the name this deadline was scheduled with. Until you name the deadline, the legacy deadline support will fire the deadline named after its payload type with a warning.
                                @CommandHandler(payloadType = PaymentDeadline.class)
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void dropsEmptyDeadlineName() {
            rewriteRun(
                    java(PAYLOAD),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                @DeadlineHandler(deadlineName = "")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order {
                                // TODO(axon4to5): the original @DeadlineHandler did not name the deadline explicitly. Name the deadline this @CommandHandler handles with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") after the name this deadline was scheduled with. Until you name the deadline, the legacy deadline support will fire the deadline named after its payload type with a warning.
                                @CommandHandler
                                void on(PaymentDeadline deadline) {
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
                                @DeadlineHandler
                                void on(Object payload) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") per deadline it handles. It names no deadline, and its payload type is no concrete class, so it handled every deadline with a payload of that type. A fired aggregate deadline now arrives as a command named <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its @AggregateRoot or @Aggregate annotation, or its simple class name.
                                @DeadlineHandler
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
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") per deadline it handles. It names no deadline, and its payload type is no concrete class, so it handled every deadline with a payload of that type. A fired aggregate deadline now arrives as a command named <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its @AggregateRoot or @Aggregate annotation, or its simple class name.
                                @DeadlineHandler
                                void on(Reminder reminder) {
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
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") per deadline it handles. It names neither a deadline nor a payload type, so it handled every deadline of the aggregate. A fired aggregate deadline now arrives as a command named <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its @AggregateRoot or @Aggregate annotation, or its simple class name.
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
    class AggregateTypes {

        @Test
        void usesTypeOfAggregateRootAnnotation() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.command.AggregateRoot;

                            @AggregateRoot(type = "order")
                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.modelling.command.AggregateRoot;

                            @AggregateRoot(type = "order")
                            class Order {
                                @CommandHandler(commandName = "order.paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesTypeOfSpringAggregateAnnotation() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(type = "order")
                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(type = "order")
                            class Order {
                                @CommandHandler(commandName = "order.paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesSimpleClassNameWhenAnnotationDeclaresNoType() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            class Order {
                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksHandlerOfPolymorphicAggregateSubclass() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.command.AggregateRoot;

                            @AggregateRoot
                            abstract class Order {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class ExpressOrder extends Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.annotation.DeadlineHandler;

                            class ExpressOrder extends Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler(commandName = "<aggregate type>.<deadline name>"), with the aggregate type of the root of this polymorphic aggregate, which Axon Framework 4 stored with the deadline. A fired aggregate deadline now arrives as a command named <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its @AggregateRoot or @Aggregate annotation, or its simple class name.
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class MemberEntities {

        private static final String ORDER = """
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
                """;

        @Test
        void marksDeadlineHandlerOfMemberEntityAndKeepsIt() {
            rewriteRun(
                    java(PAYLOAD),
                    java(ORDER),
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

                            import org.axonframework.deadline.annotation.DeadlineHandler;
                            import org.axonframework.modelling.command.EntityId;

                            class OrderLine {
                                @EntityId
                                private String lineId;

                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler on the aggregate root, or one this entity receives through its routing key. Axon Framework 4 delivered a fired deadline to the aggregate root and to every member entity, while the command it now arrives as reaches an entity in a collection only through a routing key.
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on(PaymentDeadline deadline) {
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

        @Test
        void replacesHandlerOfAggregateThatSharesSuperclassWithSaga() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            abstract class BaseComponent {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class OrderSaga extends BaseComponent {
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

                            class Order extends BaseComponent {
                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                void on() {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Order extends BaseComponent {
                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on() {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class KotlinClasses {

        @Test
        void replacesDeadlineHandlerOfKotlinAggregateWithCommandHandler() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            class PaymentDeadline(val orderId: String)
                            """
                    ),
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.command.AggregateIdentifier

                            class Order {
                                @AggregateIdentifier
                                private lateinit var orderId: String

                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                fun on(deadline: PaymentDeadline) {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.modelling.command.AggregateIdentifier

                            class Order {
                                @AggregateIdentifier
                                private lateinit var orderId: String

                                @CommandHandler(commandName = "Order.paymentDeadline")
                                fun on(deadline: PaymentDeadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsParametersOfKotlinHandlerWithoutPayload() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.messaging.annotation.MetaDataValue

                            class Order {
                                @DeadlineHandler(deadlineName = "reminder")
                                fun onReminder() {
                                }

                                @DeadlineHandler(deadlineName = "expiry")
                                fun onExpiry(@MetaDataValue("origin") origin: String) {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.messaging.annotation.MetaDataValue

                            class Order {
                                @CommandHandler(commandName = "Order.reminder")
                                fun onReminder() {
                                }

                                @CommandHandler(commandName = "Order.expiry")
                                fun onExpiry(@MetaDataValue("origin") origin: String) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void resolvesKotlinConstantsForDeadlineNameAndAggregateType() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.command.AggregateRoot

                            @AggregateRoot(type = Order.TYPE)
                            class Order {
                                companion object {
                                    const val TYPE = "order"
                                    const val PAYMENT_DEADLINE = "paymentDeadline"
                                }

                                @DeadlineHandler(deadlineName = PAYMENT_DEADLINE)
                                fun on() {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.modelling.command.AggregateRoot

                            @AggregateRoot(type = Order.TYPE)
                            class Order {
                                companion object {
                                    const val TYPE = "order"
                                    const val PAYMENT_DEADLINE = "paymentDeadline"
                                }

                                @CommandHandler(commandName = "order.paymentDeadline")
                                fun on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void namesKotlinHandlerWithoutDeadlineNameAfterItsPayloadType() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.DeadlineManager
                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import java.time.Duration

                            class PaymentDeadline(val orderId: String)

                            class Order {
                                fun schedule(deadlineManager: DeadlineManager, orderId: String) {
                                    deadlineManager.schedule(Duration.ofDays(1), "paymentDeadline", PaymentDeadline(orderId))
                                }

                                @DeadlineHandler
                                fun on(deadline: PaymentDeadline) {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.deadline.DeadlineManager
                            import java.time.Duration

                            class PaymentDeadline(val orderId: String)

                            class Order {
                                fun schedule(deadlineManager: DeadlineManager, orderId: String) {
                                    deadlineManager.schedule(Duration.ofDays(1), "paymentDeadline", PaymentDeadline(orderId))
                                }

                                // TODO(axon4to5): the original @DeadlineHandler did not name the deadline explicitly. Name the deadline this @CommandHandler handles with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") after the name this deadline was scheduled with. Until you name the deadline, the legacy deadline support will fire the deadline named after its payload type with a warning.
                                @CommandHandler
                                fun on(deadline: PaymentDeadline) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void keepsKotlinPayloadTypeClassReference() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            class PaymentDeadline(val orderId: String)
                            """
                    ),
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler

                            class Order {
                                @DeadlineHandler(deadlineName = "paymentDeadline", payloadType = PaymentDeadline::class)
                                fun on() {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler

                            class Order {
                                @CommandHandler(commandName = "Order.paymentDeadline", payloadType = PaymentDeadline::class)
                                fun on() {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksKotlinHandlerWithAnyPayloadAndNoDeadlineName() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler

                            class Order {
                                @DeadlineHandler
                                fun on(payload: Any) {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler

                            class Order {
                                // TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler(commandName = "<aggregate type>.<deadline name>") per deadline it handles. It names no deadline, and its payload type is no concrete class, so it handled every deadline with a payload of that type. A fired aggregate deadline now arrives as a command named <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its @AggregateRoot or @Aggregate annotation, or its simple class name.
                                @DeadlineHandler
                                fun on(payload: Any) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesDeadlineHandlerOfKotlinSagaAlone() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.deadline.annotation.DeadlineHandler
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: String) {
                                }

                                @DeadlineHandler(deadlineName = "paymentDeadline")
                                fun onDeadline() {
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
                                @CommandHandler(commandName = "Order.reminder")
                                void onReminder() {
                                }

                                @CommandHandler(commandName = "Order.paymentDeadline")
                                void on(PaymentDeadline deadline) {
                                }
                            }
                            """
                    )
            );
        }
    }
}
