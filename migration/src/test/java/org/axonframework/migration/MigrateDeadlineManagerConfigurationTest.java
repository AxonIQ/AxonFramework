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

    private static final String COMPONENT_TODO =
            "// TODO(axon4to5): register a DeadlineManager component or Spring bean, unless the application already"
                    + " does. Axon Framework 4 registered a SimpleDeadlineManager by default; Axoniq Framework 5"
                    + " registers none, except the JobRunr and db-scheduler deadline managers of its Spring Boot"
                    + " auto-configuration.";
    private static final String CONFIGURER_TODO =
            "// TODO(axon4to5): register the DeadlineManager as a component instead, with componentRegistry(registry"
                    + " -> registry.registerComponent(DeadlineManager.class, configuration -> ...)) on the Axon"
                    + " Framework 5 configurer, or as a Spring bean. Axon Framework 5 configurers have no"
                    + " configureDeadlineManager(..); Axon Framework 4 also registered a SimpleDeadlineManager by"
                    + " default, Axoniq Framework 5 registers none.";
    private static final String INTERCEPTOR_TODO =
            "// TODO(axon4to5): register this interceptor on the concrete deadline manager instance, such as the"
                    + " SimpleDeadlineManager you build, before registering it as a component. The DeadlineManager"
                    + " interface no longer declares registerDispatchInterceptor(..) and registerHandlerInterceptor(..);"
                    + " AbstractDeadlineManager does, with Axon Framework 5 interceptor types.";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateDeadlineManagerConfiguration())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class RemovedConfigurationApi {

        @Test
        void marksConfigureDeadlineManagerOnAFluentConfigurer() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.config.Configuration;
                            import org.axonframework.config.DefaultConfigurer;
                            import org.axonframework.deadline.SimpleDeadlineManager;

                            class Application {
                                Configuration configuration() {
                                    return DefaultConfigurer.defaultConfiguration()
                                                            .configureDeadlineManager(config -> SimpleDeadlineManager.builder()
                                                                                                                     .scopeAwareProvider(config.scopeAwareProvider())
                                                                                                                     .build())
                                                            .buildConfiguration();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.config.Configuration;
                            import org.axonframework.config.DefaultConfigurer;
                            import org.axonframework.deadline.SimpleDeadlineManager;

                            class Application {
                                Configuration configuration() {
                                    %s
                                    return DefaultConfigurer.defaultConfiguration()
                                                            .configureDeadlineManager(config -> SimpleDeadlineManager.builder()
                                                                                                                     .scopeAwareProvider(config.scopeAwareProvider())
                                                                                                                     .build())
                                                            .buildConfiguration();
                                }
                            }
                            """.formatted(CONFIGURER_TODO)
                    )
            );
        }

        @Test
        void replacesDeadlineManagerLookupAndMarksInterceptorRegisteredOnIt() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.config.Configuration;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.MessageHandlerInterceptor;

                            class Application {
                                void start(Configuration configuration,
                                           MessageHandlerInterceptor<? super DeadlineMessage<?>> interceptor) {
                                    configuration.deadlineManager().registerHandlerInterceptor(interceptor);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.config.Configuration;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.MessageHandlerInterceptor;

                            class Application {
                                void start(Configuration configuration,
                                           MessageHandlerInterceptor<? super DeadlineMessage<?>> interceptor) {
                                    %s
                                    %s
                                    configuration.getComponent(DeadlineManager.class).registerHandlerInterceptor(interceptor);
                                }
                            }
                            """.formatted(INTERCEPTOR_TODO, COMPONENT_TODO)
                    )
            );
        }

        @Test
        void marksDispatchInterceptorRegisteredOnInjectedDeadlineManager() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.MessageDispatchInterceptor;

                            class DeadlineInterceptors {
                                private final DeadlineManager deadlineManager;

                                DeadlineInterceptors(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                void register(MessageDispatchInterceptor<? super DeadlineMessage<?>> interceptor) {
                                    deadlineManager.registerDispatchInterceptor(interceptor);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.MessageDispatchInterceptor;

                            class DeadlineInterceptors {
                                private final DeadlineManager deadlineManager;

                                DeadlineInterceptors(DeadlineManager deadlineManager) {
                                    this.deadlineManager = deadlineManager;
                                }

                                void register(MessageDispatchInterceptor<? super DeadlineMessage<?>> interceptor) {
                                    %s
                                    deadlineManager.registerDispatchInterceptor(interceptor);
                                }
                            }
                            """.formatted(INTERCEPTOR_TODO)
                    )
            );
        }

        @Test
        void leavesInterceptorRegisteredOnConcreteDeadlineManagerAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.MessageHandlerInterceptor;

                            class DeadlineInterceptors {
                                void register(SimpleDeadlineManager deadlineManager,
                                              MessageHandlerInterceptor<? super DeadlineMessage<?>> interceptor) {
                                    deadlineManager.registerHandlerInterceptor(interceptor);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesUserMethodNamedDeadlineManagerAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;

                            class DeadlineConfig {
                                private final DeadlineConfig delegate = new DeadlineConfig();

                                DeadlineManager deadlineManager() {
                                    return null;
                                }

                                DeadlineManager scheduler() {
                                    return delegate.deadlineManager();
                                }
                            }
                            """
                    )
            );
        }
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
