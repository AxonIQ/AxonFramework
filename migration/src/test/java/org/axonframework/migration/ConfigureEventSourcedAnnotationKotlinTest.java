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

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies that {@link ConfigureEventSourcedAnnotation} emits Kotlin-shaped class literals
 * ({@code X::class.java}) when the source is Kotlin. Java sources keep producing
 * {@code X.class} — covered by the Java test suite.
 */
class ConfigureEventSourcedAnnotationKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Drive the umbrella so AddEventTagAnnotation populates the cross-recipe id-type
        // map before ConfigureEventSourcedAnnotation reads it. Two cycles: first renames
        // @Aggregate → @EventSourced, second adds the explicit attributes.
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
            .typeValidationOptions(TypeValidation.none())
            .expectedCyclesThatMakeChanges(1);
    }

    @Test
    void emitsKotlinClassLiteralForDeducedIdType() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Astrologers {
                            @AggregateIdentifier
                            private lateinit var astrologersId: String

                            private constructor()
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
                        import org.axonframework.extension.spring.stereotype.EventSourced

                        @EventSourced(tagKey = "Astrologers", idType = String::class.java)
                        class Astrologers {
                            private lateinit var astrologersId: String

                            @EntityCreator
                            private constructor()
                        }
                        """
                )
        );
    }

    @Test
    void migratesAuctionHouseStyleKotlinAggregateEndToEnd() {
        // Mirrors the auction-house demo `Auction.kt` shape: AF4 @Aggregate, nested `enum class`,
        // a property with a getter (`winningBid`'s `get() = ...`), an @AggregateIdentifier field,
        // a no-arg `private constructor()`, a multi-arg @CommandHandler constructor, and a
        // non-constructor @CommandHandler that calls `AggregateLifecycle.apply(...)`. The umbrella
        // recipe must:
        //  - rename @Aggregate → @EventSourced(tagKey="Auction", idType=String::class.java) (Kotlin
        //    class-literal, not Java's `String.class`)
        //  - leave the `winningBid` getter alone (must not pick up @EntityCreator)
        //  - lift the @CommandHandler constructor into a `companion object` with @JvmStatic
        //  - inject `eventAppender: EventAppender` into the non-constructor @CommandHandler and
        //    rewrite `apply(...)` → `eventAppender.append(...)`
        //  - put @EntityCreator on the no-arg `private constructor()`
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.modelling.command.AggregateLifecycle
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            @AggregateIdentifier
                            private lateinit var id: String
                            private val bids = mutableListOf<Bid>()
                            private val winningBid: Bid?
                                get() = bids.maxByOrNull { it.amount }

                            enum class State {
                                CREATED, STARTED, ENDED
                            }

                            @CommandHandler
                            constructor(cmd: CreateAuction) {
                                AggregateLifecycle.apply(cmd)
                            }

                            @CommandHandler
                            fun on(cmd: PlaceBidOnAuction) {
                                AggregateLifecycle.apply(cmd)
                            }

                            private constructor()
                        }

                        data class Bid(val participant: String, val amount: Long)
                        class CreateAuction
                        class PlaceBidOnAuction
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
                        import org.axonframework.extension.spring.stereotype.EventSourced
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler
                        import org.axonframework.messaging.eventhandling.gateway.EventAppender

                        @EventSourced(tagKey = "Auction", idType = String::class.java)
                        class Auction {
                            private lateinit var id: String
                            private val bids = mutableListOf<Bid>()
                            private val winningBid: Bid?
                                get() = bids.maxByOrNull { it.amount }

                            enum class State {
                                CREATED, STARTED, ENDED
                            }

                            @CommandHandler
                            fun on(cmd: PlaceBidOnAuction, eventAppender: EventAppender) {
                                eventAppender.append(cmd)
                            }

                            @EntityCreator
                            private constructor()
                            companion object {
                                @JvmStatic
                                @CommandHandler
                                fun handle(cmd: CreateAuction, eventAppender: EventAppender) {
                                    eventAppender.append(cmd)
                                }
                            }
                        }

                        data class Bid(val participant: String, val amount: Long)

                        @Command
                        class CreateAuction

                        @Command
                        class PlaceBidOnAuction
                        """
                )
        );
    }

    @Test
    void emitsKotlinTodoFallbackWhenIdTypeUnknown() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Foo {
                            private constructor()
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
                        import org.axonframework.extension.spring.stereotype.EventSourced

                        @EventSourced(tagKey = "Foo", idType = Object::class.java /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */)
                        class Foo {
                            @EntityCreator
                            private constructor()
                        }
                        """
                )
        );
    }
}
