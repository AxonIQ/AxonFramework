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
        // is named `id`, but the event class uses `auctionId` - not a name-similarity match either
        // ("id" is not a suffix of "auctionId"), so the recipe falls back to the first
        // primary-constructor param and annotates it with a trailing TODO comment for the human
        // reviewer to verify the choice.
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
                            @EventTag(key = "Auction") // TODO(axon4to5): verify this is the aggregate-id field
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

    @Test
    void flagsTypeAndNameSimilarityMatchOnPrimaryConstructorParam() {
        // "id" is a case-insensitive suffix of the entity's "giftCardId" and both are Strings, so the
        // param is tagged. It is no exact match, so a TODO is left for the reviewer.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private lateinit var giftCardId: String

                            @EventSourcingHandler
                            fun on(event: CardIssued) {}

                            private constructor()
                        }

                        data class CardIssued(
                            val id: String,
                            val amount: Int,
                        )
                        """,
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.eventsourcing.annotation.EventTag
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private lateinit var giftCardId: String

                            @EventSourcingHandler
                            fun on(event: CardIssued) {}

                            private constructor()
                        }

                        data class CardIssued(
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            val id: String,
                            val amount: Int,
                        )
                        """
                )
        );
    }

    @Test
    void flagsSiblingKotlinCommandTargetIdAlias() {
        // Neither exact name nor similarity matches "giftCardId" and "identifier". The data class
        // command in the same package targets the entity through a param named "identifier", which
        // selects the event's param.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.eventsourcing.EventSourcingHandler
                        import org.axonframework.modelling.command.AggregateIdentifier
                        import org.axonframework.spring.stereotype.Aggregate

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private lateinit var giftCardId: String

                            @EventSourcingHandler
                            fun on(event: CardIssued) {}

                            private constructor()
                        }
                        """
                ),
                kotlin(
                        """
                        package com.example
                        import org.axonframework.modelling.command.TargetAggregateIdentifier

                        data class IssueCard(
                            @TargetAggregateIdentifier val identifier: String,
                        )
                        """
                ),
                kotlin(
                        """
                        package com.example

                        data class CardIssued(
                            val amount: Int,
                            val identifier: String,
                        )
                        """,
                        """
                        package com.example

                        import org.axonframework.eventsourcing.annotation.EventTag

                        data class CardIssued(
                            val amount: Int,
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            val identifier: String,
                        )
                        """
                )
        );
    }
}
