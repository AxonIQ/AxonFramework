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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link MigrateDeadlineManagerConfiguration}: Axon Framework 4 configuration code around a deadline manager
 * that the legacy module does not keep is replaced where its successor is clear.
 */
class MigrateDeadlineManagerConfigurationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateDeadlineManagerConfiguration())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class ConfigurationScopeAwareProviderReplacement {

        @Test
        void replacesConfigurationScopeAwareProviderWithTheConfiguredComponent() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.config.Configuration;
                            import org.axonframework.config.ConfigurationScopeAwareProvider;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                ScopeAwareProvider scopeAwareProvider(Configuration configuration) {
                                    return new ConfigurationScopeAwareProvider(configuration);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.config.Configuration;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                ScopeAwareProvider scopeAwareProvider(Configuration configuration) {
                                    return configuration.getComponent(ScopeAwareProvider.class);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void replacesConfigurationScopeAwareProviderInsideBuilderChain() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.config.ConfigurationScopeAwareProvider;
                            import org.axonframework.config.Configurer;
                            import org.axonframework.deadline.SimpleDeadlineManager;

                            class DeadlineConfig {
                                void configure(Configurer configurer) {
                                    configurer.registerComponent(
                                            SimpleDeadlineManager.class,
                                            config -> SimpleDeadlineManager.builder()
                                                                           .scopeAwareProvider(new ConfigurationScopeAwareProvider(config))
                                                                           .build()
                                    );
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.config.Configurer;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                void configure(Configurer configurer) {
                                    configurer.registerComponent(
                                            SimpleDeadlineManager.class,
                                            config -> SimpleDeadlineManager.builder()
                                                                           .scopeAwareProvider(config.getComponent(ScopeAwareProvider.class))
                                                                           .build()
                                    );
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesUserClassNamedConfigurationScopeAwareProviderAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            class ConfigurationScopeAwareProvider {
                                ConfigurationScopeAwareProvider(Object configuration) {
                                }
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            class Scheduling {
                                Object provider(Object configuration) {
                                    return new ConfigurationScopeAwareProvider(configuration);
                                }
                            }
                            """
                    )
            );
        }
    }
}
