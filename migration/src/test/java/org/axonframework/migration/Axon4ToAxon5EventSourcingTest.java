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
 * Verifies the event-sourcing-module migration: {@code @EventSourcingHandler}
 * moves into the {@code .annotation} subpackage.
 */
class Axon4ToAxon5EventSourcingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5EventSourcing"));
    }

    @Test
    void renamesEventSourcingHandlerIntoAnnotationSubpackage() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        class Foo {
                            @EventSourcingHandler
                            void on(Object evt) {}
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

                        class Foo {
                            @EventSourcingHandler
                            void on(Object evt) {}
                        }
                        """
                )
        );
    }

    @Test
    void addsEntityCreatorToNoArgConstructorOfSpringAggregate() {
        // Drives the full `UpgradeAxon4ToAxon5` chain so the AF4 `@Aggregate`
        // Spring stereotype gets rewritten to `@EventSourced` first, then the
        // AddEntityCreatorAnnotation recipe annotates the no-arg constructor.
        // Using AF4 input keeps types resolvable against the test classpath
        // (we only ship AF4 jars in test scope). Two cycles are needed
        // because the AF4 `@Aggregate` rename runs first; only the second
        // cycle sees a class annotated with the AF5 `@EventSourced`
        // stereotype that AddEntityCreatorAnnotation looks for.
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
                        .typeValidationOptions(TypeValidation.none())
                        .expectedCyclesThatMakeChanges(1),
                java(
                        """
                        package com.example;
                        import org.axonframework.spring.stereotype.Aggregate;
                        @Aggregate
                        class GiftCard {
                            GiftCard() { }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
                        import org.axonframework.extension.spring.stereotype.EventSourced;

                        @EventSourced(tagKey = "GiftCard", idType = Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */)
                        class GiftCard {
                            @EntityCreator
                            GiftCard() { }
                        }
                        """
                )
        );
    }

    @Test
    void addsEntityCreatorWhenConstructorIsAtEndOfClassBodyAfterHandlers() {
        // Mirrors the structure of Heroes' `Army.java` — the no-arg
        // constructor is the last member, after several command/event handlers.
        // Earlier failures showed the constructor was being skipped in this
        // layout despite the unit test for the simpler "constructor first"
        // shape passing; this test pins the regression.
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
                        .typeValidationOptions(TypeValidation.none())
                        .expectedCyclesThatMakeChanges(1),
                java(
                        """
                        package com.example;
                        import org.axonframework.spring.stereotype.Aggregate;
                        import org.axonframework.commandhandling.CommandHandler;
                        @Aggregate
                        class Order {
                            @CommandHandler
                            void handle(Object cmd) {}
                            Order() {
                                // required by Axon
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
                        import org.axonframework.extension.spring.stereotype.EventSourced;
                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

                        @EventSourced(tagKey = "Order", idType = Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */)
                        class Order {
                            @CommandHandler
                            void handle(Object cmd) {}

                            @EntityCreator
                            Order() {
                                // required by Axon
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesUnrelatedClassesAlone() {
        // The recipe must skip classes that are NOT annotated with
        // `@EventSourcedEntity` / `@EventSourced`. POJOs with no-arg
        // constructors should remain unchanged.
        rewriteRun(
                java(
                        """
                        package com.example;
                        class PlainPojo {
                            PlainPojo() { }
                        }
                        """
                )
        );
    }

    @Test
    void deducesEventSourcedIdTypeFromAggregateIdentifierField() {
        // End-to-end: when the AF4 source has an `@AggregateIdentifier` field,
        // the umbrella must produce `@EventSourced(... idType = <Type>.class)`
        // instead of the `Object.class` TODO placeholder. The two recipes
        // involved (`AddEventTagAnnotation` in modelling, then
        // `ConfigureEventSourcedAnnotation` in spring-extension) communicate
        // through the shared `ExecutionContext` so the type captured before
        // `@AggregateIdentifier` is removed survives until the placeholder is
        // generated.
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
                        .typeValidationOptions(TypeValidation.none())
                        .expectedCyclesThatMakeChanges(1),
                java(
                        """
                        package com.example;
                        import java.util.UUID;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;
                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private UUID cardId;
                            GiftCard() { }
                        }
                        """,
                        """
                        package com.example;
                        import java.util.UUID;

                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
                        import org.axonframework.extension.spring.stereotype.EventSourced;

                        @EventSourced(tagKey = "GiftCard", idType = UUID.class)
                        class GiftCard {
                            private UUID cardId;

                            @EntityCreator
                            GiftCard() { }
                        }
                        """
                )
        );
    }

    @Test
    void replacesAggregateLifecycleApplyWithInjectedEventAppender() {
        // AF4-style aggregate using the static `AggregateLifecycle.apply`
        // import — after the full migration, the call resolves to an
        // injected `EventAppender#append(...)`. Same 2-cycle reason as the
        // previous test.
        rewriteRun(
                spec -> spec.recipe(Environment.builder()
                                            .scanRuntimeClasspath("org.axonframework.migration")
                                            .build()
                                            .activateRecipes(
                                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
                        .typeValidationOptions(TypeValidation.none())
                        .expectedCyclesThatMakeChanges(1),
                java(
                        """
                        package com.example;
                        import org.axonframework.spring.stereotype.Aggregate;
                        import static org.axonframework.modelling.command.AggregateLifecycle.apply;
                        @Aggregate
                        class GiftCard {
                            GiftCard() { }
                            void handle(Object cmd) {
                                apply(cmd);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
                        import org.axonframework.extension.spring.stereotype.EventSourced;
                        import org.axonframework.messaging.eventhandling.gateway.EventAppender;

                        @EventSourced(tagKey = "GiftCard", idType = Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */)
                        class GiftCard {
                            @EntityCreator
                            GiftCard() { }
                            void handle(Object cmd, EventAppender eventAppender) {
                                eventAppender.append(cmd);
                            }
                        }
                        """
                )
        );
    }
}
