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
 * Verifies the {@link AddEntityCreatorAnnotation} recipe on Kotlin sources.
 * <p>
 * The fixtures intentionally include a nested {@code enum class} alongside the no-arg
 * constructor — that combination is what surfaces Kotlin-only syntax into the surrounding
 * class context. A naive {@code JavaTemplate}-based annotation addition would render the
 * surrounding class as a Java placeholder during template compilation, and the Java parser
 * would fail on {@code enum class State {}} (Kotlin's nested enum form). Building the
 * {@code J.Annotation} directly via LST avoids that parse path entirely.
 */
class AddEntityCreatorAnnotationKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The migration module ships only AF4 type stubs in test scope, so the
        // AF5 `@EntityCreator` annotation that this recipe produces has no
        // resolvable type. Disable strict type validation so the test asserts on
        // the textual transformation, which is the contract the recipe owns.
        spec.recipe(new AddEntityCreatorAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void addsEntityCreatorToNoArgConstructorOfKotlinAggregateWithNestedEnum() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            private lateinit var id: String

                            enum class State {
                                CREATED, STARTED, ENDED
                            }

                            private constructor()
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            private lateinit var id: String

                            enum class State {
                                CREATED, STARTED, ENDED
                            }

                            @EntityCreator
                            private constructor()
                        }
                        """
                )
        );
    }

    @Test
    void isIdempotentWhenConstructorIsAlreadyAnnotated() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            private lateinit var id: String

                            enum class State {
                                CREATED, STARTED, ENDED
                            }

                            @EntityCreator
                            private constructor()
                        }
                        """
                )
        );
    }

    @Test
    void doesNotAnnotateKotlinPropertyGetters() {
        // Reproduces the auction-house demo regression: `private val winningBid: Bid? get() = ...`
        // is a Kotlin property accessor, NOT a no-arg constructor. The recipe must skip it even
        // though the underlying LST surfaces the getter as a no-arg J.MethodDeclaration.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            private val bids = mutableListOf<Int>()
                            private val winningBid: Int?
                                get() = bids.maxOrNull()

                            private constructor()
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            private val bids = mutableListOf<Int>()
                            private val winningBid: Int?
                                get() = bids.maxOrNull()

                            @EntityCreator
                            private constructor()
                        }
                        """
                )
        );
    }

    @Test
    void leavesNonAggregateKotlinClassesUntouched() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        class NotAnAggregate {
                            enum class State { A, B }

                            private constructor()
                        }
                        """
                )
        );
    }
}
