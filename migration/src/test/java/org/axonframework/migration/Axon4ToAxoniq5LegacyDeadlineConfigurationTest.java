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
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies that the full Axoniq Framework 5 upgrade migrates an Axon Framework 4 deadline manager configuration, with
 * the legacy deadline steps running before the free Axon Framework 5 leg renames the Axon Framework 4 packages.
 */
class Axon4ToAxoniq5LegacyDeadlineConfigurationTest implements RewriteTest {

    private static final String UNIT_OF_WORK_FACTORY_TODO =
            "// TODO(axon4to5): add unitOfWorkFactory(..) to this deadline manager builder, for example"
                    + " configuration.getComponent(UnitOfWorkFactory.class) or the UnitOfWorkFactory Spring bean."
                    + " The legacy deadline managers require it in place of transactionManager(..): a fired deadline"
                    + " runs in a unit of work, and transaction, of that factory.";
    private static final String SERIALIZER_TODO =
            "// TODO(axon4to5): replace serializer(..) with converter(..), passing the Converter matching the Axon"
                    + " Framework 4 serializer, so stored deadlines stay readable: general serializer ->"
                    + " GeneralConverter, message serializer -> MessageConverter, event serializer -> EventConverter,"
                    + " a JacksonSerializer with a customized ObjectMapper -> a JacksonConverter configured the same"
                    + " way, XStreamSerializer -> XStreamConverter allowing your payload and scope types.";
    private static final String PROVIDER_TODO =
            "// TODO(axon4to5): remove this ScopeAwareProvider registration. Axoniq Framework 5 registers its own"
                    + " ScopeAwareProvider component, with which the Saga managers register themselves; a provider"
                    + " registered here replaces it, and would look itself up.";
    private static final String CONFIGURER_TODO =
            "// TODO(axon4to5): register the DeadlineManager as a component instead, with componentRegistry(registry"
                    + " -> registry.registerComponent(DeadlineManager.class, configuration -> ...)) on the Axon"
                    + " Framework 5 configurer, or as a Spring bean. Axon Framework 5 configurers have no"
                    + " configureDeadlineManager(..); Axon Framework 4 also registered a SimpleDeadlineManager by"
                    + " default, Axoniq Framework 5 registers none.";
    private static final String COMPONENT_TODO =
            "// TODO(axon4to5): register a DeadlineManager component or Spring bean, unless the application already"
                    + " does. Axon Framework 4 registered a SimpleDeadlineManager by default; Axoniq Framework 5"
                    + " registers none, except the JobRunr and db-scheduler deadline managers of its Spring Boot"
                    + " auto-configuration.";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
            .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY)
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void migratesSpringQuartzDeadlineManagerBean() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.common.transaction.TransactionManager;
                        import org.axonframework.config.ConfigurationScopeAwareProvider;
                        import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                        import org.axonframework.serialization.Serializer;
                        import org.quartz.Scheduler;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        class DeadlineConfiguration {

                            @Bean
                            ConfigurationScopeAwareProvider scopeAwareProvider(org.axonframework.config.Configuration config) {
                                return new ConfigurationScopeAwareProvider(config);
                            }

                            @Bean
                            QuartzDeadlineManager deadlineManager(Scheduler scheduler,
                                                                  org.axonframework.config.Configuration config,
                                                                  TransactionManager transactionManager,
                                                                  @Qualifier("eventSerializer") Serializer serializer) {
                                return QuartzDeadlineManager.builder()
                                                            .scheduler(scheduler)
                                                            .scopeAwareProvider(new ConfigurationScopeAwareProvider(config))
                                                            .transactionManager(transactionManager)
                                                            .serializer(serializer)
                                                            .build();
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.common.transaction.TransactionManager;
                        import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                        import org.axonframework.messaging.ScopeAwareProvider;
                        import org.axonframework.conversion.Serializer;
                        import org.quartz.Scheduler;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        class DeadlineConfiguration {

                            %s
                            @Bean
                            ScopeAwareProvider scopeAwareProvider(org.axonframework.common.configuration.Configuration config) {
                                return config.getComponent(ScopeAwareProvider.class);
                            }

                            @Bean
                            QuartzDeadlineManager deadlineManager(Scheduler scheduler,
                                                                  org.axonframework.common.configuration.Configuration config,
                                                                  TransactionManager transactionManager,
                                                                  @Qualifier("eventSerializer") Serializer serializer) {
                                %s
                                %s
                                return QuartzDeadlineManager.builder()
                                                            .scheduler(scheduler)
                                                            .scopeAwareProvider(config.getComponent(ScopeAwareProvider.class))
                                                            .serializer(serializer)
                                                            .build();
                            }
                        }
                        """.formatted(PROVIDER_TODO, UNIT_OF_WORK_FACTORY_TODO, SERIALIZER_TODO)
                )
        );
    }

    @Test
    void migratesPlainJavaConfigurerDeadlineManager() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.common.transaction.TransactionManager;
                        import org.axonframework.config.Configuration;
                        import org.axonframework.config.ConfigurationScopeAwareProvider;
                        import org.axonframework.config.DefaultConfigurer;
                        import org.axonframework.deadline.DeadlineManagerSpanFactory;
                        import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                        import org.axonframework.serialization.json.JacksonSerializer;
                        import org.jobrunr.scheduling.JobScheduler;

                        class Application {

                            Configuration configuration(JobScheduler jobScheduler) {
                                Configuration configuration =
                                        DefaultConfigurer.defaultConfiguration()
                                                         .configureDeadlineManager(config -> JobRunrDeadlineManager.builder()
                                                                                                                   .jobScheduler(jobScheduler)
                                                                                                                   .scopeAwareProvider(new ConfigurationScopeAwareProvider(config))
                                                                                                                   .serializer(JacksonSerializer.defaultSerializer())
                                                                                                                   .transactionManager(config.getComponent(TransactionManager.class))
                                                                                                                   .spanFactory(config.getComponent(DeadlineManagerSpanFactory.class))
                                                                                                                   .build())
                                                         .buildConfiguration();
                                configuration.start();
                                configuration.deadlineManager().cancelAll("rentalExpired");
                                return configuration;
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.common.configuration.Configuration;
                        import org.axonframework.common.configuration.DefaultConfigurer;
                        import org.axonframework.conversion.jackson.JacksonConverter;
                        import org.axonframework.deadline.DeadlineManager;
                        import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                        import org.axonframework.messaging.ScopeAwareProvider;
                        import org.jobrunr.scheduling.JobScheduler;

                        class Application {

                            Configuration configuration(JobScheduler jobScheduler) {
                                %s
                                %s
                                Configuration configuration =
                                        DefaultConfigurer.defaultConfiguration()
                                                         .configureDeadlineManager(config -> JobRunrDeadlineManager.builder()
                                                                                                                   .jobScheduler(jobScheduler)
                                                                                                                   .scopeAwareProvider(config.getComponent(ScopeAwareProvider.class))
                                                                                                                   .converter(new JacksonConverter())
                                                                                                                   .build())
                                                         .buildConfiguration();
                                configuration.start();
                                %s
                                configuration.getComponent(DeadlineManager.class).cancelAll("rentalExpired");
                                return configuration;
                            }
                        }
                        """.formatted(UNIT_OF_WORK_FACTORY_TODO, CONFIGURER_TODO, COMPONENT_TODO)
                )
        );
    }
}
