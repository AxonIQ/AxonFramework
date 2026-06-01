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
 * Verifies the {@link AddMessageOriginProviderSpringBeanConfiguration} recipe creates (or updates)
 * a {@code CorrelationDataProviderConfiguration} Spring {@code @Configuration} class.
 * <p>
 * The recipe triggers <strong>only</strong> on a no-args {@code new MessageOriginProvider()}
 * constructor call — explicit-arg forms such as
 * {@code new MessageOriginProvider("myKey", "myOther")} are left untouched so that developers who
 * intentionally chose custom key names are not overridden.
 * <p>
 * The generated / injected bean uses {@code new MessageOriginProvider("traceId", "correlationId")}:
 * {@code correlationKey="traceId"} preserves the AF4 trace-id key name, and
 * {@code causationKey="correlationId"} preserves the AF4 correlation-id key name.
 */
class AddMessageOriginProviderSpringBeanConfigurationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddMessageOriginProviderSpringBeanConfiguration())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void generatesConfigClassWhenNoneExistsInSpringBootApp() {
        // when: @SpringBootApplication class present + no-args MessageOriginProvider usage
        // then: a new CorrelationDataProviderConfiguration is generated in the root package.
        // Note: JavaParser preserves the blank lines from the generated source template —
        // blank line between `package` and imports, blank line inside the class body.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        class Application {}
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class SomeHandler {
                            private MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """
                ),
                java(
                        null,
                        """
                        package com.example;

                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        public class CorrelationDataProviderConfiguration {

                            @Bean
                            public CorrelationDataProvider messageOriginProvider() {
                                return new MessageOriginProvider("traceId", "correlationId");
                            }
                        }
                        """,
                        spec -> spec.path("src/main/java/com/example/CorrelationDataProviderConfiguration.java")
                )
        );
    }

    @Test
    void addsBeanToExistingConfigClassWhenBeanAbsent() {
        // when: CorrelationDataProviderConfiguration exists but lacks the @Bean method
        // then: the @Bean method is injected by JavaTemplate (no blank line after `package`
        // because the original file didn't have one; no blank line inside {} because the
        // original body was empty).
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        class Application {}
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class SomeHandler {
                            private MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        public class CorrelationDataProviderConfiguration {
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        public class CorrelationDataProviderConfiguration {
                            @Bean
                            public CorrelationDataProvider messageOriginProvider() {
                                return new MessageOriginProvider("traceId", "correlationId");
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesExistingBeanMethodUntouched() {
        // Idempotency: if the @Bean already exists, re-running should produce no change.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        class Application {}
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class SomeHandler {
                            private MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        public class CorrelationDataProviderConfiguration {

                            @Bean
                            public CorrelationDataProvider messageOriginProvider() {
                                return new MessageOriginProvider("traceId", "correlationId");
                            }
                        }
                        """
                )
        );
    }

    @Test
    void doesNotTriggerOnExplicitConstructorArgs() {
        // Developer used custom key names intentionally — recipe must NOT override them.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        class Application {}
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class SomeHandler {
                            private MessageOriginProvider provider =
                                new MessageOriginProvider("myCorrelation", "myCausation");
                        }
                        """
                )
                // no generated file expected — explicit args prevent the trigger
        );
    }

    @Test
    void doesNotOverrideBeanWithExplicitArgs() {
        // Developer already set custom key names in the @Bean — do not touch.
        // The scanner detects explicit args in the return statement and sets the
        // "do not override" flag so neither the visitor nor the generator runs.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        class Application {}
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class SomeHandler {
                            private MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """
                ),
                // config class exists with custom args in the @Bean → must remain unchanged
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        public class CorrelationDataProviderConfiguration {

                            @Bean
                            public CorrelationDataProvider messageOriginProvider() {
                                return new MessageOriginProvider("myCustomCorrelation", "myCustomCausation");
                            }
                        }
                        """
                )
        );
    }

    @Test
    void doesNothingWhenNoMessageOriginProviderUsage() {
        // No MessageOriginProvider in the project → no config class generated.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        class Application {}
                        """
                )
        );
    }

    @Test
    void doesNothingWithoutSpringBootApplication() {
        // Non-Spring-Boot project: no @SpringBootApplication → can't determine package,
        // so no file is generated even when no-args MessageOriginProvider is used.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.correlation.MessageOriginProvider;

                        class SomeHandler {
                            private MessageOriginProvider provider = new MessageOriginProvider();
                        }
                        """
                )
        );
    }
}
