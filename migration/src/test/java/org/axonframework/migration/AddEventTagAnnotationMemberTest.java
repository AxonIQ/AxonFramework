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
 * Verifies that {@link AddEventTagAnnotation} tags the events of a child entity declared via
 * {@code @AggregateMember}/{@code @EntityMember}, not just the root aggregate's own events. The
 * child event is tagged with the <b>parent</b> entity's tag, so it is sourced into the parent's
 * stream, which is what an Axon Framework 4 aggregate and its members shared.
 */
class AddEventTagAnnotationMemberTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddEventTagAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void tagsMemberChildEventWithParentTag() {
        rewriteRun(
                // parent aggregate with an @AggregateMember child
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.modelling.command.AggregateMember;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String cardId;

                            @AggregateMember
                            private Transaction transaction;

                            @EventSourcingHandler
                            void on(CardIssued event) {
                            }
                        }
                        """
                ),
                // child entity with its own @EventSourcingHandler
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;

                        class Transaction {
                            private String transactionId;

                            @EventSourcingHandler
                            void on(CardRedeemed event) {
                            }
                        }
                        """
                ),
                // parent event: tagged with the aggregate id field (existing behaviour)
                java(
                        """
                        package com.example;

                        class CardIssued {
                            String cardId;
                            int amount;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssued {
                            @EventTag(key = "GiftCard")
                            String cardId;
                            int amount;
                        }
                        """
                ),
                // child event: carries the parent id field, must now be tagged with the parent tag
                java(
                        """
                        package com.example;

                        class CardRedeemed {
                            String cardId;
                            String transactionId;
                            int amount;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardRedeemed {
                            @EventTag(key = "GiftCard")
                            String cardId;
                            String transactionId;
                            int amount;
                        }
                        """
                )
        );
    }

    @Test
    void tagsMemberChildEventForListMember() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import java.util.List;
                        import org.axonframework.eventsourcing.EventSourcingHandler;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.modelling.command.AggregateMember;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class GiftCard {
                            @AggregateIdentifier
                            private String cardId;

                            @AggregateMember
                            private List<Transaction> transactions;

                            @EventSourcingHandler
                            void on(CardIssued event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;

                        class Transaction {
                            private String transactionId;

                            @EventSourcingHandler
                            void on(CardRedeemed event) {
                            }
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardIssued {
                            String cardId;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardIssued {
                            @EventTag(key = "GiftCard")
                            String cardId;
                        }
                        """
                ),
                java(
                        """
                        package com.example;

                        class CardRedeemed {
                            String cardId;
                            String transactionId;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.eventsourcing.annotation.EventTag;

                        class CardRedeemed {
                            @EventTag(key = "GiftCard")
                            String cardId;
                            String transactionId;
                        }
                        """
                )
        );
    }
}
