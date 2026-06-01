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
 * Verifies the {@link MigrateMessageOriginProviderDefaultKeys} recipe replaces no-args
 * {@code new MessageOriginProvider()} with {@code new MessageOriginProvider("traceId", "correlationId")}
 * to preserve AF4-compatible metadata key names.
 * <p>
 * Key mapping (AF4 → AF5 rename, what this recipe preserves):
 * <ul>
 *   <li>AF4 {@code "traceId"} (originating-message id, propagated) → AF5 {@code correlationKey}</li>
 *   <li>AF4 {@code "correlationId"} (current-message id, direct cause) → AF5 {@code causationKey}</li>
 * </ul>
 */
class MigrateMessageOriginProviderDefaultKeysTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMessageOriginProviderDefaultKeys())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void replacesNoArgsConstructorWithAf4Keys() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.correlation.MessageOriginProvider;

                        class MyConfiguration {
                            MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.correlation.MessageOriginProvider;

                        class MyConfiguration {
                            MessageOriginProvider provider = new MessageOriginProvider("traceId", "correlationId");
                        }
                        """
                )
        );
    }

    @Test
    void replacesNoArgsConstructorOnAf5FqnType() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class MyConfiguration {
                            MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class MyConfiguration {
                            MessageOriginProvider provider = new MessageOriginProvider("traceId", "correlationId");
                        }
                        """
                )
        );
    }

    @Test
    void leavesConstructorWithExplicitArgsUntouched() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class MyConfiguration {
                            MessageOriginProvider provider = new MessageOriginProvider("myCorrelation", "myCausation");
                        }
                        """
                )
        );
    }

    @Test
    void leavesAlreadyMigratedConstructorUntouched() {
        // Idempotency: if the recipe already ran, re-running should produce no change.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class MyConfiguration {
                            MessageOriginProvider provider = new MessageOriginProvider("traceId", "correlationId");
                        }
                        """
                )
        );
    }

    @Test
    void leavesOtherClassConstructorsUntouched() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        class MyConfiguration {
                            Object obj = new Object();
                        }
                        """
                )
        );
    }

    @Test
    void replacesInMethodBody() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.correlation.MessageOriginProvider;
                        import org.axonframework.messaging.correlation.CorrelationDataProvider;

                        class Config {
                            CorrelationDataProvider provider() {
                                return new MessageOriginProvider();
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.correlation.MessageOriginProvider;
                        import org.axonframework.messaging.correlation.CorrelationDataProvider;

                        class Config {
                            CorrelationDataProvider provider() {
                                return new MessageOriginProvider("traceId", "correlationId");
                            }
                        }
                        """
                )
        );
    }
}
