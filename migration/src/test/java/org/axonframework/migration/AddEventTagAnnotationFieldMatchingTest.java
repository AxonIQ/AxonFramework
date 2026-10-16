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
 * "tag the first field + leave a TODO for human review". These cover a real-world gap found while
 * migrating a sample application: the entity's {@code @AggregateIdentifier} field was named
 * {@code giftCardId}, while the corresponding event class carried the same identifier under a
 * differently-named field (e.g. plain {@code id}), which a naive exact-name match would miss.
 */
class AddEventTagAnnotationFieldMatchingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddEventTagAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void matchesByTypeAndNameSimilarityWhenExactNameFails() {
        // Entity id field is "giftCardId"; the event's own field is just "id". Not an exact match,
        // but "giftCardId" ends with "id" (case-insensitively) and both are declared String — the
        // type-plus-name-similarity heuristic should tag it directly, without a TODO.
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
                            @EventTag(key = "GiftCard")
                            String id;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void matchesSiblingCommandTargetEntityIdFieldAlias() {
        // Neither an exact-name nor a name-similarity match exists between the entity's id field
        // ("giftCardId") and the event's field ("identifier"). But a command class in the same
        // package routes to the entity via a @TargetAggregateIdentifier field also named
        // "identifier" — that alias, combined with the matching String type, should be preferred
        // over the "first field" fallback.
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
                            @EventTag(key = "GiftCard")
                            String identifier;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void fallsBackWithTodoWhenTypeDiffersDespiteNameSimilarity() {
        // The event's "id" field would satisfy the name-similarity check against entity id field
        // "giftCardId", but it's declared long instead of String — the type mismatch must block the
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
}
