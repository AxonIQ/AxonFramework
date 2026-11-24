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
 * Verifies the field-matching heuristics {@link AddEventTagAnnotation} tries before falling back to
 * "tag the first field". These cover a real-world gap found while migrating a sample application:
 * the entity's {@code @AggregateIdentifier} field was named {@code giftCardId}, while the
 * corresponding event class carried the same identifier under a differently-named field (e.g. plain
 * {@code id}), which a naive exact-name match would miss. Only an exact name match is certain, so
 * every heuristic match leaves a TODO for human review.
 */
class AddEventTagAnnotationFieldMatchingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddEventTagAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void flagsTypeAndNameSimilarityMatchForReviewWhenExactNameFails() {
        // Entity id field is "giftCardId"; the event's own field is just "id". Not an exact match,
        // but "giftCardId" ends with "id" (case-insensitively) and both are declared String - the
        // type-plus-name-similarity heuristic should tag it, but leave a TODO as it is no exact match.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardIssuedEvent {
                            String id;
                            int amount;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssuedEvent {
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            String id;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void flagsSiblingCommandTargetEntityIdFieldAliasForReview() {
        // Neither an exact-name nor a name-similarity match exists between the entity's id field
        // ("giftCardId") and the event's field ("identifier"). But a command class in the same
        // package routes to the entity via a @TargetAggregateIdentifier field also named
        // "identifier" - that alias, combined with the matching String type, selects the field,
        // but leaves a TODO as it is no exact match.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.modelling.command.TargetAggregateIdentifier;

                        class IssueCardCommand {
                            @TargetAggregateIdentifier
                            private String identifier;
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardIssuedEvent {
                            String identifier;
                            int amount;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssuedEvent {
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            String identifier;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void flagsTheFirstDeclaredFieldForReviewWhenSeveralFieldsAreSimilar() {
        // Both "id" (the event's own identifier) and "cardId" end the entity id field name
        // "giftCardId". The first declared field wins, which can be the wrong one, so the choice
        // must be flagged for a human reviewer.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardIssuedEvent {
                            String id;
                            String cardId;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssuedEvent {
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            String id;
                            String cardId;
                        }
                        """
                )
        );
    }

    @Test
    void fallsBackWithTodoWhenTypeDiffersDespiteNameSimilarity() {
        // The event's "id" field would satisfy the name-similarity check against entity id field
        // "giftCardId", but it's declared long instead of String - the type mismatch must block the
        // heuristic match, leaving the original "first field + TODO" fallback in place.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardIssuedEvent {
                            long id;
                            int amount;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssuedEvent {
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            long id;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void usesSiblingCommandAliasEvenWhenTheCommandTargetsADifferentEntity() {
        // Known limitation, pinned on purpose: the sibling-command alias is collected per package, not
        // per entity. The Order command's "orderId" therefore also selects the "orderId" field of the
        // GiftCard event. The match is still flagged, so a human gets to verify it.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.modelling.command.TargetAggregateIdentifier;

                        class PlaceOrderCommand {
                            @TargetAggregateIdentifier
                            private String orderId;
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardIssuedEvent {
                            String orderId;
                            int amount;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssuedEvent {
                            @EventTag(key = "GiftCard") // TODO(axon4to5): verify this is the aggregate-id field
                            String orderId;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void tagsRecordEventComponentWithoutReviewWhenNameMatchesExactly() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        record CardIssuedEvent(String giftCardId, int amount) {
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        record CardIssuedEvent(@EventTag(key = "GiftCard") String giftCardId, int amount) {
                        }
                        """
                )
        );
    }

    @Test
    void flagsRecordEventComponentMatchedThroughRecordCommandTargetId() {
        // The record command declares its target id as a record component; that component name is the
        // alias that selects the record event's component. The review marker is a block comment because
        // the component list sits on one line, where a line comment would swallow the rest of it.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String giftCardId;

                            @EventSourcingHandler
                            void on(CardIssuedEvent event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.modelling.command.TargetAggregateIdentifier;

                        record IssueCardCommand(@TargetAggregateIdentifier String identifier) {
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        record CardIssuedEvent(String identifier, int amount) {
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        record CardIssuedEvent(@EventTag(key = "GiftCard") /* TODO(axon4to5): verify this is the aggregate-id field */ String identifier, int amount) {
                        }
                        """
                )
        );
    }
}
