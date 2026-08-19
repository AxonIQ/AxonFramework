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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the {@link ConvertCommandHandlerConstructorToStaticMethod} recipe rewrites AF4
 * {@code @CommandHandler} constructors into AF5-compatible {@code public static void handle(...)}
 * methods.
 */
class ConvertCommandHandlerConstructorToStaticMethodTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConvertCommandHandlerConstructorToStaticMethod())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void convertsAf4CommandHandlerConstructor() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;

                        public class Payment {

                            private String id;

                            public Payment() {
                            }

                            @CommandHandler
                            public Payment(PreparePaymentCommand command, EventAppender eventAppender) {
                                String paymentId = "p-1";
                                eventAppender.append(new PaymentPreparedEvent(paymentId, command.amount()));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;

                        public class Payment {

                            private String id;

                            public Payment() {
                            }

                            @CommandHandler
                            public static void handle(PreparePaymentCommand command, EventAppender eventAppender) {
                                String paymentId = "p-1";
                                eventAppender.append(new PaymentPreparedEvent(paymentId, command.amount()));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void convertsAf5CommandHandlerConstructor() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

                        public class Payment {
                            @CommandHandler
                            public Payment(PreparePaymentCommand command, EventAppender eventAppender) {
                                eventAppender.append(new PaymentPreparedEvent(command.amount()));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

                        public class Payment {
                            @CommandHandler
                            public static void handle(PreparePaymentCommand command, EventAppender eventAppender) {
                                eventAppender.append(new PaymentPreparedEvent(command.amount()));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesNonCommandHandlerConstructorsAlone() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        public class Payment {
                            public Payment(String id) {
                                this.id = id;
                            }

                            private String id;
                        }
                        """
                )
        );
    }

    @Test
    void leavesCommandHandlerMethodsAlone() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

                        public class Payment {
                            @CommandHandler
                            public void handle(ConfirmPaymentCommand command) {
                            }
                        }
                        """
                )
        );
    }
}
