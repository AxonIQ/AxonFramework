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
 * Verifies that {@link AnnotateProgrammaticSequencingPolicyRegistration} attaches a
 * {@code // TODO(axon4to5):} comment above
 * {@code EventProcessingConfigurer#registerSequencingPolicy(...)} call sites and is
 * idempotent on re-runs.
 */
class AnnotateProgrammaticSequencingPolicyRegistrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AnnotateProgrammaticSequencingPolicyRegistration())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void annotatesProgrammaticRegistration() {
        rewriteRun(
                java(
                        """
                        package org.axonframework.config;
                        import java.util.function.Function;
                        public interface EventProcessingConfigurer {
                            EventProcessingConfigurer registerSequencingPolicy(String processingGroup,
                                                                                Function<Object, Object> policyBuilder);
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.config.EventProcessingConfigurer;
                        class Config {
                            void configure(EventProcessingConfigurer configurer) {
                                configurer.registerSequencingPolicy("orders", cfg -> new Object());
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.config.EventProcessingConfigurer;
                        class Config {
                            void configure(EventProcessingConfigurer configurer) {
                                // TODO(axon4to5): EventProcessingConfigurer#registerSequencingPolicy is gone in AF5. Replace with either @SequencingPolicy on the event handler class (org.axonframework.messaging.core.annotation.SequencingPolicy) or, for processor-level configuration, EventProcessorDefinition….customized(c -> c.sequencingPolicy(...)) (org.axonframework.extension.spring.config.EventProcessorDefinition).
                                configurer.registerSequencingPolicy("orders", cfg -> new Object());
                            }
                        }
                        """
                )
        );
    }

    @Test
    void isIdempotentWhenAlreadyAnnotated() {
        // Re-running the recipe over the post-migration shape must not append a second
        // TODO comment. The check matches by the stable marker substring.
        rewriteRun(
                java(
                        """
                        package org.axonframework.config;
                        import java.util.function.Function;
                        public interface EventProcessingConfigurer {
                            EventProcessingConfigurer registerSequencingPolicy(String processingGroup,
                                                                                Function<Object, Object> policyBuilder);
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.config.EventProcessingConfigurer;
                        class Config {
                            void configure(EventProcessingConfigurer configurer) {
                                // TODO(axon4to5): EventProcessingConfigurer#registerSequencingPolicy is gone in AF5. Replace with either @SequencingPolicy on the event handler class (org.axonframework.messaging.core.annotation.SequencingPolicy) or, for processor-level configuration, EventProcessorDefinition….customized(c -> c.sequencingPolicy(...)) (org.axonframework.extension.spring.config.EventProcessorDefinition).
                                configurer.registerSequencingPolicy("orders", cfg -> new Object());
                            }
                        }
                        """
                )
        );
    }

    @Test
    void annotatesPostRenameAf5Configurer() {
        // `Axon4ToAxon5Common` renames `org.axonframework.config.EventProcessingConfigurer`
        // into `org.axonframework.messaging.eventhandling.configuration.EventProcessingConfigurer`
        // before this recipe runs. The MethodMatcher binds to the LST's resolved type, so the
        // recipe must also match the AF5 FQN — otherwise it silently no-ops on every codebase
        // that ran the umbrella in order.
        rewriteRun(
                java(
                        """
                        package org.axonframework.messaging.eventhandling.configuration;
                        import java.util.function.Function;
                        public interface EventProcessingConfigurer {
                            EventProcessingConfigurer registerSequencingPolicy(String processingGroup,
                                                                                Function<Object, Object> policyBuilder);
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.configuration.EventProcessingConfigurer;
                        class Config {
                            void configure(EventProcessingConfigurer configurer) {
                                configurer.registerSequencingPolicy("orders", cfg -> new Object());
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.configuration.EventProcessingConfigurer;
                        class Config {
                            void configure(EventProcessingConfigurer configurer) {
                                // TODO(axon4to5): EventProcessingConfigurer#registerSequencingPolicy is gone in AF5. Replace with either @SequencingPolicy on the event handler class (org.axonframework.messaging.core.annotation.SequencingPolicy) or, for processor-level configuration, EventProcessorDefinition….customized(c -> c.sequencingPolicy(...)) (org.axonframework.extension.spring.config.EventProcessorDefinition).
                                configurer.registerSequencingPolicy("orders", cfg -> new Object());
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesUnrelatedCallsAlone() {
        // Method-pattern matching is scoped to `EventProcessingConfigurer#registerSequencingPolicy`.
        // A same-named method on an unrelated type must not pick up the TODO.
        rewriteRun(
                java(
                        """
                        package com.example;
                        class Other {
                            void registerSequencingPolicy(String name, Object policy) {
                            }
                            void use() {
                                registerSequencingPolicy("foo", new Object());
                            }
                        }
                        """
                )
        );
    }
}
