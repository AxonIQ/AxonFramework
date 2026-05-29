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

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies the {@link AddCommandAnnotation} recipe on Kotlin sources. The
 * common shape in Kotlin is a {@code data class} with a primary constructor
 * parameter annotated {@code @RoutingKey}; the lift moves the routing-key
 * declaration onto a class-level {@code @Command(routingKey = "...")}
 * annotation and drops the now-unused {@code @RoutingKey} import.
 */
class AddCommandAnnotationKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The migration module ships only AF4 type stubs in test scope, so the
        // AF5 `@Command` annotation that this recipe produces — and that the
        // post-migration / idempotency fixtures reference — has no resolvable
        // type. Disable strict type validation so the test asserts on the
        // textual transformation, which is the contract the recipe owns.
        spec.recipe(new AddCommandAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void liftsRoutingKeyFromKotlinDataClassPrimaryConstructorParameter() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.commandhandling.RoutingKey

                        data class PreparePaymentCommand(@RoutingKey val paymentReference: String)

                        class PaymentCommandHandler {
                            @CommandHandler
                            fun on(cmd: PreparePaymentCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command

                        @Command(routingKey = "paymentReference")
                        data class PreparePaymentCommand(val paymentReference: String)

                        class PaymentCommandHandler {
                            @CommandHandler
                            fun on(cmd: PreparePaymentCommand) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void liftsRoutingKeyFromMultiParamDataClassWhereRoutingKeyIsNotFirst() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.commandhandling.RoutingKey

                        data class PreparePaymentCommand(
                            val amount: Long,
                            @RoutingKey val paymentReference: String
                        )

                        class PaymentCommandHandler {
                            @CommandHandler
                            fun on(cmd: PreparePaymentCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command

                        @Command(routingKey = "paymentReference")
                        data class PreparePaymentCommand(
                            val amount: Long,
                            val paymentReference: String
                        )

                        class PaymentCommandHandler {
                            @CommandHandler
                            fun on(cmd: PreparePaymentCommand) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void isIdempotentWhenAlreadyMigrated() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command

                        @Command(routingKey = "paymentReference")
                        data class PreparePaymentCommand(val paymentReference: String)

                        class PaymentCommandHandler {
                            @CommandHandler
                            fun on(cmd: PreparePaymentCommand) {
                            }
                        }
                        """
                )
        );
    }
}
