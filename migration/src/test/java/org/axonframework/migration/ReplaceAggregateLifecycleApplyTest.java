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
import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Pins the {@link ReplaceAggregateLifecycleApply} carve-outs that the umbrella tests in
 * {@code Axon4ToAxon5EventSourcingTest} don't cover directly:
 * <ul>
 *   <li>Constructors are left untouched even when they call {@code AggregateLifecycle.apply(...)}.
 *   AF5 has no command associated with construction, so there's nothing for the framework to
 *   inject; the recipe leaves the call so the developer can rework it (typically by lifting the
 *   creation event onto the static command-handler that produces the entity).</li>
 *   <li>The carve-out is language-agnostic — Kotlin secondary constructors with Kotlin-shaped
 *   parameters (`name: Type`) are passed through without trying to compile a Java placeholder
 *   for them, which previously crashed the recipe on the auction-house demo's
 *   {@code Auction.kt}.</li>
 * </ul>
 */
class ReplaceAggregateLifecycleApplyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The migration module ships only AF4 type stubs in test scope.
        spec.recipe(new ReplaceAggregateLifecycleApply())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void leavesJavaConstructorAlone() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import static org.axonframework.modelling.command.AggregateLifecycle.apply;
                        class GiftCard {
                            GiftCard(Object cmd) {
                                apply(cmd);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesKotlinSecondaryConstructorAlone() {
        // Reproduces the auction-house demo's failure shape: a Kotlin secondary constructor
        // whose parameter list (`name: Type`) is not parseable as Java. The recipe must skip
        // it before it tries to JavaTemplate-replace the parameters.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.modelling.command.AggregateLifecycle

                        class GiftCard {
                            constructor(cmd: Any) {
                                AggregateLifecycle.apply(cmd)
                            }
                        }
                        """
                )
        );
    }

    @Test
    void stillRewritesNonConstructorMethodInJava() {
        // Sanity: the carve-out is constructor-only — regular methods still get the
        // EventAppender-injection treatment.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import static org.axonframework.modelling.command.AggregateLifecycle.apply;
                        class GiftCard {
                            void handle(Object cmd) {
                                apply(cmd);
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.gateway.EventAppender;

                        class GiftCard {
                            void handle(Object cmd, EventAppender eventAppender) {
                                eventAppender.append(cmd);
                            }
                        }
                        """
                )
        );
    }
}
