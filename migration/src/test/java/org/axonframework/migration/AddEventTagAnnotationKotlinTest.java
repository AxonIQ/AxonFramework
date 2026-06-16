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
 * Verifies the {@link AddEventTagAnnotation} recipe on Kotlin sources. Kotlin
 * {@code data class} event payloads with primary-constructor params need the same
 * {@code @EventTag(key = "EntityName")} treatment that Java events get on their fields.
 * The aggregate's identifier-field name is read from the entity class (a Kotlin
 * {@code @Aggregate}-annotated class with a {@code lateinit var id: String} field) and
 * matched against the event's primary-constructor params.
 */
class AddEventTagAnnotationKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddEventTagAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void annotatesFirstParamAsFallbackWhenIdFieldNameDiffers() {
        // Mirrors the auction-house Auction.kt shape: the entity's @AggregateIdentifier field
        // is named `id`, but the event class uses `auctionId`. The recipe must fall back to
        // the first primary-constructor param and annotate it (with a TODO comment for the
        // human reviewer, omitted here since the test only pins the annotation placement).
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            @AggregateIdentifier
                            private lateinit var id: String

                            @EventSourcingHandler
                            fun on(event: AuctionCreated) {}

                            private constructor()
                        }

                        data class AuctionCreated(
                            val auctionId: String,
                            val owner: String,
                        )
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.eventsourcing.annotation.EventTag
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            @AggregateIdentifier
                            private lateinit var id: String

                            @EventSourcingHandler
                            fun on(event: AuctionCreated) {}

                            private constructor()
                        }

                        data class AuctionCreated(
                            @EventTag(key = "Auction")
                            val auctionId: String,
                            val owner: String,
                        )
                        """
                )
        );
    }

    @Test
    void annotatesMatchingPrimaryConstructorParamWhenNamesMatch() {
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            @AggregateIdentifier
                            private lateinit var auctionId: String

                            @EventSourcingHandler
                            fun on(event: AuctionCreated) {}

                            private constructor()
                        }

                        data class AuctionCreated(
                            val auctionId: String,
                            val owner: String,
                        )
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.eventsourcing.annotation.EventTag
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class Auction {
                            @AggregateIdentifier
                            private lateinit var auctionId: String

                            @EventSourcingHandler
                            fun on(event: AuctionCreated) {}

                            private constructor()
                        }

                        data class AuctionCreated(
                            @EventTag(key = "Auction") 
                            val auctionId: String,
                            val owner: String,
                        )
                        """
                )
        );
    }
}
