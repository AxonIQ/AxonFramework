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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies migration of Axon Framework 4 deadline test fixtures onto {@code io.axoniq.framework:axoniq-legacy-test}.
 */
class Axon4ToAxoniq5LegacyDeadlineTestFixtureTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(recipe("io.axoniq.framework.migration.Axon4ToAxoniq5LegacyDeadline"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class DeadlineMessageTypeArguments {

        @Test
        void removesTheTypeArgumentsOfTheNonGenericLegacyDeadlineMessages() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.GenericDeadlineMessage;
                            import org.hamcrest.Matcher;

                            import java.util.List;

                            class DeadlineMessages {
                                Matcher<? super DeadlineMessage<?>> matcher;
                                List<DeadlineMessage<String>> triggered;

                                DeadlineMessage<?> create() {
                                    GenericDeadlineMessage<String> message = new GenericDeadlineMessage<>("deadlineName");
                                    return message;
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.GenericDeadlineMessage;
                            import org.hamcrest.Matcher;

                            import java.util.List;

                            class DeadlineMessages {
                                Matcher<? super DeadlineMessage> matcher;
                                List<DeadlineMessage> triggered;

                                DeadlineMessage create() {
                                    GenericDeadlineMessage message = new GenericDeadlineMessage("deadlineName");
                                    return message;
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAUserTypeNamedDeadlineMessageAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            class DeadlineMessage<T> {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            class Deadlines {
                                DeadlineMessage<String> message;
                            }
                            """
                    )
            );
        }
    }

    private static Recipe recipe(String name) {
        return Environment.builder()
                          .scanRuntimeClasspath("org.axonframework.migration")
                          .build()
                          .activateRecipes(name);
    }
}
