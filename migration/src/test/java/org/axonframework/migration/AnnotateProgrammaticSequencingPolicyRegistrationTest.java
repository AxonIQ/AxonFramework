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
                                // TODO(axon4to5): declarative sequencing-policy registration is no longer supported. Move the policy onto the event handler class via @SequencingPolicy (org.axonframework.messaging.core.annotation.SequencingPolicy).
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
                                // TODO(axon4to5): declarative sequencing-policy registration is no longer supported. Move the policy onto the event handler class via @SequencingPolicy (org.axonframework.messaging.core.annotation.SequencingPolicy).
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
