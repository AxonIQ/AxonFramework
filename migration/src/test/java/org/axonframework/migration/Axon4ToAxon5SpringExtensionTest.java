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
 * Verifies the Spring extension migration: package move from
 * {@code org.axonframework.spring} to {@code org.axonframework.extension.spring}
 * plus the {@code @Aggregate} → {@code @EventSourced} stereotype rename.
 */
class Axon4ToAxon5SpringExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5SpringExtension"));
    }

    @Test
    void renamesAggregateStereotypeToEventSourcedAndFallsBackWhenNoIdField() {
        // No `@AggregateIdentifier` field is present, so the recipe can't deduce the
        // identifier type and emits the `Object.class` placeholder with a TODO comment.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.spring.stereotype.Aggregate;
                        @Aggregate
                        class Order {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.extension.spring.stereotype.EventSourced;

                        @EventSourced(tagKey = "Order", idType = Object.class /* TODO #LLM: set to actual id type, e.g. String.class or UUID.class */)
                        class Order {}
                        """
                )
        );
    }

    @Test
    void deducesIdTypeFromAggregateIdentifierFieldOfJavaLangType() {
        // The aggregate's `@AggregateIdentifier` field is a `String`, so the recipe
        // emits `idType = String.class` — no extra import needed for `java.lang` types.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;

                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class Order {
                            @AggregateIdentifier
                            private String orderId;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.extension.spring.stereotype.EventSourced;
                        import org.axonframework.modelling.command.AggregateIdentifier;

                        @EventSourced(tagKey = "Order", idType = String.class)
                        class Order {
                            @AggregateIdentifier
                            private String orderId;
                        }
                        """
                )
        );
    }

    @Test
    void emitsTodoCommentWhenSnapshotTriggerDefinitionIsDropped() {
        // AF4 `@Aggregate(snapshotTriggerDefinition = "...")` rewrites to
        // `@EventSourced(...)`, but AF5's `@EventSourced` has no
        // `snapshotTriggerDefinition` attribute. The recipe must NOT silently drop the
        // configuration — instead, it surfaces a `// TODO #LLM` comment above the
        // annotation so a reviewer (human or LLM) can rewire the snapshot trigger
        // through AF5's configuration APIs.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;

                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate(snapshotTriggerDefinition = "dwellingSnapshotTrigger")
                        class Dwelling {
                            @AggregateIdentifier
                            private String dwellingId;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.extension.spring.stereotype.EventSourced;
                        import org.axonframework.modelling.command.AggregateIdentifier;

                        // TODO #LLM: reconfigure snapshot trigger (AF4 had snapshotTriggerDefinition = "dwellingSnapshotTrigger")
                        @EventSourced(tagKey = "Dwelling", idType = String.class)
                        class Dwelling {
                            @AggregateIdentifier
                            private String dwellingId;
                        }
                        """
                )
        );
    }

    @Test
    void deducesIdTypeFromAggregateIdentifierFieldOfNonJavaLangType() {
        // For non-`java.lang` types (here `java.util.UUID`) the recipe must add an
        // import alongside `idType = UUID.class`.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;

                        import java.util.UUID;
                        import org.axonframework.modelling.command.AggregateIdentifier;
                        import org.axonframework.spring.stereotype.Aggregate;

                        @Aggregate
                        class Order {
                            @AggregateIdentifier
                            private UUID orderId;
                        }
                        """,
                        """
                        package com.example;

                        import java.util.UUID;

                        import org.axonframework.extension.spring.stereotype.EventSourced;
                        import org.axonframework.modelling.command.AggregateIdentifier;

                        @EventSourced(tagKey = "Order", idType = UUID.class)
                        class Order {
                            @AggregateIdentifier
                            private UUID orderId;
                        }
                        """
                )
        );
    }
}
