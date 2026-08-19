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
    void explicitRoutingKeyWinsOverTargetEntityIdOnKotlinDataClass() {
        // AF4 routing semantics: an explicit @RoutingKey wins over @TargetEntityId even when the
        // target-identifier parameter is declared first. The Kotlin primary-constructor
        // parameters travel the fallback path, so this also guards the capture-precedence logic
        // there: the routing key is lifted from shardKey, its @RoutingKey annotation and import
        // are removed, and @TargetEntityId is preserved on orderId.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.commandhandling.RoutingKey
                        import org.axonframework.modelling.annotation.TargetEntityId

                        data class ReassignCommand(
                            @TargetEntityId val orderId: String,
                            @RoutingKey val shardKey: String
                        )

                        class ReassignHandler {
                            @CommandHandler
                            fun on(cmd: ReassignCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.annotation.TargetEntityId

                        @Command(routingKey = "shardKey")
                        data class ReassignCommand(
                            @TargetEntityId val orderId: String,
                            val shardKey: String
                        )

                        class ReassignHandler {
                            @CommandHandler
                            fun on(cmd: ReassignCommand) {
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

    @Test
    void liftsRoutingKeyFromTargetAggregateIdentifierOnKotlinDataClass() {
        // AF4 commonly marks the routing identifier with `@TargetAggregateIdentifier`. AF5
        // splits the concern: `@TargetEntityId` stays on the field (it tells the framework
        // which field carries the id) and `@Command(routingKey = "…")` declares the routing
        // key on the class. This isolation test exercises `AddCommandAnnotation` alone,
        // so the AF4 field annotation FQN survives — the `TargetAggregateIdentifier` →
        // `TargetEntityId` rename is owned by `Axon4ToAxon5Modelling`. The combined behaviour
        // is exercised in {@link #liftsRoutingKeyFromTargetEntityIdAfterModellingRename}.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.modelling.command.TargetAggregateIdentifier
                        import java.util.UUID

                        data class NoteAddCommand(
                            @TargetAggregateIdentifier val id: UUID,
                            val targetId: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.command.TargetAggregateIdentifier
                        import java.util.UUID

                        @Command(routingKey = "id")
                        data class NoteAddCommand(
                            @TargetAggregateIdentifier val id: UUID,
                            val targetId: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void liftsRoutingKeyFromTargetEntityIdAfterModellingRename() {
        // Post-rename shape: after `Axon4ToAxon5Modelling` finishes,
        // `@TargetAggregateIdentifier` already lives at its AF5 home as `@TargetEntityId`.
        // `AddCommandAnnotation` must still detect it and lift the routing key.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.modelling.annotation.TargetEntityId
                        import java.util.UUID

                        data class NoteAddCommand(
                            @TargetEntityId val id: UUID,
                            val targetId: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.annotation.TargetEntityId
                        import java.util.UUID

                        @Command(routingKey = "id")
                        data class NoteAddCommand(
                            @TargetEntityId val id: UUID,
                            val targetId: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void addsRoutingKeyToExistingCommandAnnotationOnKotlinDataClass() {
        // A Kotlin data class that already has a bare @Command (e.g., from an earlier incomplete
        // migration) must receive a routingKey attribute when @TargetAggregateIdentifier is
        // present on one of its primary-constructor parameters.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.command.TargetAggregateIdentifier
                        import java.util.UUID

                        @Command
                        data class NoteAddCommand(
                            @TargetAggregateIdentifier val id: UUID,
                            val body: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.command.TargetAggregateIdentifier
                        import java.util.UUID

                        @Command(routingKey = "id")
                        data class NoteAddCommand(
                            @TargetAggregateIdentifier val id: UUID,
                            val body: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void addsRoutingKeyToExistingCommandAnnotationWithTargetEntityIdOnKotlinDataClass() {
        // Post-rename shape: @TargetEntityId on the primary-constructor parameter,
        // bare @Command already on the class. Recipe must add routingKey attribute.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.annotation.TargetEntityId
                        import java.util.UUID

                        @Command
                        data class NoteAddCommand(
                            @TargetEntityId val id: UUID,
                            val body: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.modelling.annotation.TargetEntityId
                        import java.util.UUID

                        @Command(routingKey = "id")
                        data class NoteAddCommand(
                            @TargetEntityId val id: UUID,
                            val body: String
                        )

                        class NoteHandler {
                            @CommandHandler
                            fun on(cmd: NoteAddCommand) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void doesNotPopulateRoutingKeyWhenNeitherRoutingKeyNorTargetIdentifierPresent() {
        // Bare command without any routing-key marker just gets a `@Command` annotation
        // with no attributes — defaulting routing inference to the framework's standard
        // behaviour (currently, the implicit "id" routing key resolution).
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler

                        data class BareCommand(val payload: String)

                        class BareHandler {
                            @CommandHandler
                            fun on(cmd: BareCommand) {
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.messaging.commandhandling.annotation.Command

                        @Command
                        data class BareCommand(val payload: String)

                        class BareHandler {
                            @CommandHandler
                            fun on(cmd: BareCommand) {
                            }
                        }
                        """
                )
        );
    }
}
