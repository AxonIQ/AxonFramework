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
 * Verifies the {@link AddCommandAnnotation} recipe on Java {@code record} sources whose
 * primary-constructor parameter (record component) is annotated with {@code @RoutingKey}.
 * <p>
 * This is the Java analogue of the Kotlin data-class lift exercised by
 * {@link AddCommandAnnotationKotlinTest} — the recipe must move the routing-key declaration
 * onto a class-level {@code @Command(routingKey = "...")} annotation, drop the now-orphaned
 * {@code @RoutingKey} parameter annotation, and remove its import.
 */
class AddCommandAnnotationJavaRecordTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddCommandAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void liftsRoutingKeyFromTargetAggregateIdentifierOnJavaRecord() {
        // The AF4 `@TargetAggregateIdentifier` field annotation is treated as a routing-key
        // source on top of the explicit `@RoutingKey`. The field annotation itself is
        // preserved (the rename to `@TargetEntityId` is owned by `Axon4ToAxon5Modelling`,
        // which is not active in this isolation test).
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        import org.axonframework.modelling.command.TargetAggregateIdentifier;

                        public record NoteAddCommand(@TargetAggregateIdentifier String id, String body) {
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        import org.axonframework.messaging.commandhandling.annotation.Command;
                        import org.axonframework.modelling.command.TargetAggregateIdentifier;

                        @Command(routingKey = "id")
                        public record NoteAddCommand(@TargetAggregateIdentifier String id, String body) {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;

                        class NoteHandler {
                            @CommandHandler
                            void on(NoteAddCommand cmd) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void liftsRoutingKeyFromTargetEntityIdOnJavaRecord() {
        // Post-`Axon4ToAxon5Modelling` shape — the field annotation has already been renamed
        // to `@TargetEntityId`. `AddCommandAnnotation` still detects it and lifts the
        // routing key onto the class.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        import org.axonframework.modelling.annotation.TargetEntityId;

                        public record NoteAddCommand(@TargetEntityId String id, String body) {
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        import org.axonframework.messaging.commandhandling.annotation.Command;
                        import org.axonframework.modelling.annotation.TargetEntityId;

                        @Command(routingKey = "id")
                        public record NoteAddCommand(@TargetEntityId String id, String body) {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;

                        class NoteHandler {
                            @CommandHandler
                            void on(NoteAddCommand cmd) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void liftsRoutingKeyFromJavaRecordComponent() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        import org.axonframework.commandhandling.RoutingKey;

                        public record PreparePaymentCommand(int amount, @RoutingKey String paymentReference) {
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        import org.axonframework.messaging.commandhandling.annotation.Command;

                        @Command(routingKey = "paymentReference")
                        public record PreparePaymentCommand(int amount, String paymentReference) {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;

                        class PaymentCommandHandler {
                            @CommandHandler
                            void on(PreparePaymentCommand cmd) {
                            }
                        }
                        """
                )
        );
    }
}
