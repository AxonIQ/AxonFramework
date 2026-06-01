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
 * End-to-end coverage for the aliased {@code AggregateLifecycle.apply} rewrite when driven
 * through the umbrella recipe instead of the individual {@link ReplaceAggregateLifecycleApply}.
 * <p>
 * Earlier review feedback reported that the first occurrence of the aliased call was being
 * left behind when the recipe ran on a real Kotlin codebase — this fixture pins the
 * end-to-end behaviour so regressions surface here.
 */
class ReplaceAggregateLifecycleApplyUmbrellaKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.UpgradeAxon4ToAxon5"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void rewritesAliasedApplyCallSiteWhenRunThroughUmbrella() {
        // Matches the shape reported in the PR review: a single `@CommandHandler`-annotated
        // function whose body has one aliased `lifecycleApply(...)` call. The umbrella must
        // rewrite that single call to `eventAppender.append(...)`.
        rewriteRun(
                kotlin(
                        """
                        package com.example
                        import org.axonframework.commandhandling.CommandHandler
                        import org.axonframework.modelling.command.AggregateLifecycle.apply as lifecycleApply

                        class AuthLog {
                            @CommandHandler
                            fun handle(cmd: RecordSuccessfulLoginCommand) {
                                lifecycleApply(SuccessfulLoginRecordedEvent(cmd.username))
                            }
                        }

                        class RecordSuccessfulLoginCommand(val username: String)
                        class SuccessfulLoginRecordedEvent(val username: String)
                        """,
                        """
                        package com.example
                        import org.axonframework.messaging.commandhandling.annotation.Command
                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler
                        import org.axonframework.messaging.eventhandling.gateway.EventAppender

                        class AuthLog {
                            @CommandHandler
                            fun handle(cmd: RecordSuccessfulLoginCommand, eventAppender: EventAppender) {
                                eventAppender.append(SuccessfulLoginRecordedEvent(cmd.username))
                            }
                        }

                        @Command
                        class RecordSuccessfulLoginCommand(val username: String)
                        class SuccessfulLoginRecordedEvent(val username: String)
                        """
                )
        );
    }
}
