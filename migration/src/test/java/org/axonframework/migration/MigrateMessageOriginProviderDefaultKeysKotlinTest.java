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
 * Validates {@link MigrateMessageOriginProviderDefaultKeys} on Kotlin sources, mirroring the
 * real-world Spring {@code @Bean} shape from the Cinema sample app.
 * <p>
 * A type stub for {@code MessageOriginProvider} is supplied so the Kotlin parser resolves the
 * call as a constructor invocation ({@code J.NewClass}). This mirrors a real migration run, where
 * the project's compile classpath provides the Axon types — without resolution Kotlin parses
 * {@code MessageOriginProvider()} as an ambiguous {@code J.MethodInvocation} instead.
 */
class MigrateMessageOriginProviderDefaultKeysKotlinTest implements RewriteTest {

    private static final String MESSAGE_ORIGIN_PROVIDER_STUB =
            """
            package org.axonframework.messaging.core.correlation
            interface CorrelationDataProvider
            class MessageOriginProvider() : CorrelationDataProvider {
                constructor(correlationKey: String, causationKey: String) : this()
            }
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMessageOriginProviderDefaultKeys())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void replacesNoArgsConstructorInKotlinSpringBean() {
        rewriteRun(
                kotlin(MESSAGE_ORIGIN_PROVIDER_STUB),
                kotlin(
                        """
                        package com.example
                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Configuration
                        internal class AxonFrameworkConfiguration {

                            @Bean
                            fun messageOriginProvider(): CorrelationDataProvider {
                                return MessageOriginProvider()
                            }
                        }
                        """,
                        """
                        package com.example
                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Configuration
                        internal class AxonFrameworkConfiguration {

                            @Bean
                            fun messageOriginProvider(): CorrelationDataProvider {
                                return MessageOriginProvider("traceId", "correlationId")
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesConstructorWithExplicitArgsUntouched() {
        rewriteRun(
                kotlin(MESSAGE_ORIGIN_PROVIDER_STUB),
                kotlin(
                        """
                        package com.example
                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Configuration
                        internal class AxonFrameworkConfiguration {

                            @Bean
                            fun messageOriginProvider(): CorrelationDataProvider {
                                return MessageOriginProvider("myCorrelation", "myCausation")
                            }
                        }
                        """
                )
        );
    }
}
