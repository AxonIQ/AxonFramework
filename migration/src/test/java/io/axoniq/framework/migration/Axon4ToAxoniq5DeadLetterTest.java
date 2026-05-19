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

package io.axoniq.framework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the Sequenced Dead-Letter Queue migration into the Axoniq
 * commercial {@code axoniq-dead-letter} module. The core DLQ types under
 * {@code messaging.deadletter} keep their package shape; the event-handling
 * DLQ types under {@code eventhandling.deadletter} move under
 * {@code messaging.eventhandling.deadletter}.
 */
class Axon4ToAxoniq5DeadLetterTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("io.axoniq.framework.migration")
                            .build()
                            .activateRecipes(
                                    "io.axoniq.framework.migration.Axon4ToAxoniq5DeadLetter"));
    }

    @Test
    void renamesCoreDlqTypesToAxoniq() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.deadletter.SequencedDeadLetterQueue;
                        class Foo {
                            SequencedDeadLetterQueue<?> queue;
                        }
                        """,
                        """
                        package com.example;
                        import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue;
                        class Foo {
                            SequencedDeadLetterQueue<?> queue;
                        }
                        """
                )
        );
    }

    @Test
    void renamesJpaSequencedDeadLetterQueueToAxoniq() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventhandling.deadletter.jpa.JpaSequencedDeadLetterQueue;
                        class Foo {
                            JpaSequencedDeadLetterQueue<?> queue;
                        }
                        """,
                        """
                        package com.example;
                        import io.axoniq.framework.messaging.eventhandling.deadletter.jpa.JpaSequencedDeadLetterQueue;
                        class Foo {
                            JpaSequencedDeadLetterQueue<?> queue;
                        }
                        """
                )
        );
    }
}
